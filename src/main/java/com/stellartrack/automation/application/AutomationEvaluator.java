package com.stellartrack.automation.application;

import com.stellartrack.automation.persistence.AutomationRunEntity;
import com.stellartrack.common.api.Freshness;
import com.stellartrack.common.domain.OperatingMode;
import com.stellartrack.common.domain.TradingMode;
import com.stellartrack.common.events.UiNotification;
import com.stellartrack.common.provider.ExchangeAvailability;
import com.stellartrack.configuration.application.ConfigurationService;
import com.stellartrack.configuration.application.ConfigurationService.ResolvedConfiguration;
import com.stellartrack.configuration.domain.BotConfigurationSettings;
import com.stellartrack.decision.application.DecisionService;
import com.stellartrack.decision.application.NewDecision;
import com.stellartrack.decision.domain.RecommendationStatus;
import com.stellartrack.decision.persistence.RecommendationEntity;
import com.stellartrack.decision.strategy.SelectionStrategy;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.market.port.MarketDataPort;
import com.stellartrack.order.application.OrderExposureQueries;
import com.stellartrack.order.application.OrderPlacementService;
import com.stellartrack.order.application.PlacementCommand;
import com.stellartrack.order.application.PlacementOutcome;
import com.stellartrack.order.control.TradingControlService;
import com.stellartrack.order.domain.OrderPurpose;
import com.stellartrack.order.risk.RiskViolation;
import com.stellartrack.order.risk.StandardRiskRules;
import com.stellartrack.staking.application.StakingService;
import com.stellartrack.staking.application.StakingService.SequenceOutcome;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * One evaluation pass for one automation run: input from the configured selection source → approved rules →
 * fail closed on stale/missing input → recommendation + immutable snapshot → in MANUAL trading mode the approval
 * queue, in AUTOMATIC trading mode submission through the shared, fully validated placement path. While Watch Mode
 * is active the run is paused: nothing is evaluated and no real bet can be proposed.
 */
@Component
public class AutomationEvaluator {

    static final String WATCH_PAUSE_REASON = "Paused: Watch Mode is running (real betting is paused)";

    public record TickResult(String pausedReason, String stopReason, int submitted) {
    }

    private final ConfigurationService configurations;
    private final MarketDataPort marketData;
    private final ProposalEngine proposals;
    private final StakingService staking;
    private final OrderExposureQueries exposure;
    private final DecisionService decisions;
    private final OrderPlacementService placement;
    private final ExchangeAvailability exchange;
    private final TradingControlService tradingControl;
    private final ApplicationEventPublisher events;
    private final Duration marketBookMaxAge;
    private final Clock clock;
    private final Map<java.util.UUID, Set<String>> evaluatedMarketsByRun = new ConcurrentHashMap<>();

    public AutomationEvaluator(ConfigurationService configurations, MarketDataPort marketData, ProposalEngine proposals,
            StakingService staking, OrderExposureQueries exposure, DecisionService decisions,
            OrderPlacementService placement, ExchangeAvailability exchange, TradingControlService tradingControl,
            ApplicationEventPublisher events,
            @Value("${stellartrack.betfair.staleness.market-book-max-age}") Duration marketBookMaxAge, Clock clock) {
        this.configurations = configurations;
        this.marketData = marketData;
        this.proposals = proposals;
        this.staking = staking;
        this.exposure = exposure;
        this.decisions = decisions;
        this.placement = placement;
        this.exchange = exchange;
        this.tradingControl = tradingControl;
        this.events = events;
        this.marketBookMaxAge = marketBookMaxAge;
        this.clock = clock;
    }

    public TickResult tick(AutomationRunEntity run, PlacementCommand.AutomationSession session) {
        if (tradingControl.tradingMode() == TradingMode.WATCH) {
            return new TickResult(WATCH_PAUSE_REASON, null, 0);
        }
        ResolvedConfiguration configuration = configurations.resolve(run.getConfigurationId(), run.getConfigurationVersion());
        BotConfigurationSettings settings = configuration.settings();
        if (!exchange.isAvailable()) {
            return new TickResult("Exchange unavailable: " + exchange.unavailableReason().orElse(""), null, 0);
        }
        SelectionStrategy strategy;
        try {
            strategy = proposals.resolveStrategy(settings);
        } catch (RuntimeException e) {
            return new TickResult(e.getMessage(), null, 0);
        }
        List<String> unusableLayers = proposals.unusableLayers(strategy);
        if (!unusableLayers.isEmpty()) {
            return new TickResult("Paused: required input not fresh (" + String.join(", ", unusableLayers) + ")", null, 0);
        }
        Set<String> evaluated = evaluatedMarketsByRun.computeIfAbsent(run.getId(), id -> ConcurrentHashMap.newKeySet());
        List<MarketSummary> candidates = proposals.candidates(settings, clock.instant()).stream()
                .filter(m -> !evaluated.contains(m.marketId())).toList();
        if (candidates.isEmpty()) {
            return new TickResult(null, null, 0);
        }
        Map<String, MarketPrices> prices = marketData.getPrices(candidates.stream().map(MarketSummary::marketId).toList())
                .stream().collect(Collectors.toMap(MarketPrices::marketId, Function.identity(), (a, b) -> a));
        String paused = null;
        int submitted = 0;
        for (MarketSummary market : candidates) {
            MarketPrices book = prices.get(market.marketId());
            Freshness freshness = book == null ? Freshness.unavailable("Betfair", "No market book")
                    : Freshness.evaluate(book.asOf(), marketBookMaxAge, book.dataDelayed(), book.source().name(), clock);
            if (freshness.state() != Freshness.State.LIVE) {
                paused = "Paused entries: market data is " + freshness.state();
                continue;
            }
            evaluated.add(market.marketId());
            MarketOutcome outcome = evaluateMarket(configuration, strategy, market, book, freshness, session);
            submitted += outcome.submitted();
            if (outcome.stopReason() != null) {
                return new TickResult(paused, outcome.stopReason(), submitted);
            }
        }
        return new TickResult(paused, null, submitted);
    }

    private record MarketOutcome(int submitted, String stopReason) {
    }

    private MarketOutcome evaluateMarket(ResolvedConfiguration configuration, SelectionStrategy strategy,
            MarketSummary market, MarketPrices book, Freshness freshness, PlacementCommand.AutomationSession session) {
        BotConfigurationSettings settings = configuration.settings();
        TradingMode tradingMode = tradingControl.tradingMode();
        if (tradingMode == TradingMode.WATCH) {
            return new MarketOutcome(0, null);
        }
        List<ProposalEngine.Proposal> evaluated = proposals.evaluate(configuration, strategy, market, book, freshness,
                (side, price) -> staking.calculate(side, price, settings.staking(), allocation(settings), sequence(configuration)));
        int submitted = 0;
        for (ProposalEngine.Proposal proposal : evaluated) {
            boolean recommend = proposal.recommend();
            // The global trading mode decides whether a human approves each proposal (see TradingControlService).
            boolean approval = tradingMode == TradingMode.MANUAL;
            RecommendationStatus status = !recommend ? RecommendationStatus.SKIPPED
                    : approval ? RecommendationStatus.PENDING_APPROVAL : RecommendationStatus.ACCEPTED;
            List<String> reasons = proposal.reasons();
            RecommendationEntity recommendation = decisions.record(new NewDecision(configuration.id(),
                    configuration.version(), OperatingMode.AUTOMATIC, settings.selection().source(), market.marketId(),
                    proposal.selectionId(), proposal.side(), market.marketType(), status, approval,
                    recommend ? null : String.join("; ", reasons.isEmpty() ? List.of("Not selected") : reasons),
                    status == RecommendationStatus.PENDING_APPROVAL
                            ? clock.instant().plusSeconds(settings.approval().approvalTimeoutSeconds()) : null,
                    proposal.snapshot()));
            if (status == RecommendationStatus.PENDING_APPROVAL) {
                events.publishEvent(UiNotification.of("recommendations", recommendation.getId(), Map.of("status", status.name())));
            }
            if (status != RecommendationStatus.ACCEPTED) {
                continue;
            }
            PlacementOutcome outcome = placement.submit(new PlacementCommand(OperatingMode.AUTOMATIC,
                    OperatingMode.AUTOMATIC, OrderPurpose.ENTRY,
                    null, recommendation.getId(), configuration, market.marketId(), proposal.selectionId(), proposal.side(),
                    proposal.price(), proposal.stake(), settings.timing().persistenceType(), "auto-" + recommendation.getId(),
                    "system", null, false, session));
            if (outcome.status() == PlacementOutcome.Status.PLACED || outcome.status() == PlacementOutcome.Status.UNKNOWN_OUTCOME) {
                submitted++;
            } else if (!outcome.violations().isEmpty()) {
                decisions.noteNotExecuted(recommendation.getId(), outcome.violations().stream()
                        .map(RiskViolation::message).collect(Collectors.joining("; ")));
                Optional<RiskViolation> stop = outcome.violations().stream()
                        .filter(v -> StandardRiskRules.SESSION_STOP_CODES.contains(v.code())).findFirst();
                if (stop.isPresent()) {
                    return new MarketOutcome(submitted, stop.get().message());
                }
            }
        }
        return new MarketOutcome(submitted, null);
    }

    private static BigDecimal allocation(BotConfigurationSettings settings) {
        return settings.capital() == null ? null : settings.capital().allocatedAmount();
    }

    private List<SequenceOutcome> sequence(ResolvedConfiguration configuration) {
        return SequenceOutcome.fromSigns(exposure.outcomeSignsAscending(configuration.id()));
    }

    public void forgetRun(java.util.UUID runId) {
        evaluatedMarketsByRun.remove(runId);
    }
}
