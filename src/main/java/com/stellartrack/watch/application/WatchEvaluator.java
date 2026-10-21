package com.stellartrack.watch.application;

import com.stellartrack.automation.application.ProposalEngine;
import com.stellartrack.common.api.Freshness;
import com.stellartrack.common.provider.ExchangeAvailability;
import com.stellartrack.configuration.application.ConfigurationService;
import com.stellartrack.configuration.application.ConfigurationService.ResolvedConfiguration;
import com.stellartrack.configuration.domain.BotConfigurationSettings;
import com.stellartrack.decision.strategy.SelectionStrategy;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.market.port.MarketDataPort;
import com.stellartrack.staking.application.BankResolver;
import com.stellartrack.staking.application.StakingService;
import com.stellartrack.staking.application.StakingService.SequenceOutcome;
import com.stellartrack.watch.domain.WatchConfigurationState;
import com.stellartrack.watch.persistence.WatchSessionConfigurationEntity;
import com.stellartrack.watch.persistence.WatchSessionConfigurationRepository;
import com.stellartrack.watch.persistence.WatchSessionEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One evaluation pass of an active Watch session: for each running configuration, the same candidate markets,
 * strategy, filters and staking as live automation ({@link ProposalEngine}), with the stake based on the virtual
 * capital and the staking sequence on the session's simulated results. Delayed market data is accepted (the fill
 * model compensates); stale or missing data pauses the configuration's entries, as it does live.
 */
@Component
public class WatchEvaluator {

    /** @param notice why entries were paused this pass, or null */
    public record ConfigurationTick(String notice, String stopReason) {
    }

    private final ConfigurationService configurations;
    private final WatchSessionConfigurationRepository sessionConfigurations;
    private final ProposalEngine proposals;
    private final MarketDataPort marketData;
    private final StakingService staking;
    private final WatchCapitalService capital;
    private final WatchExposureQueries history;
    private final WatchExecutionService execution;
    private final ExchangeAvailability exchange;
    private final Duration marketBookMaxAge;
    private final Clock clock;
    private final Map<UUID, Set<String>> evaluatedMarkets = new ConcurrentHashMap<>();

    public WatchEvaluator(ConfigurationService configurations, WatchSessionConfigurationRepository sessionConfigurations,
            ProposalEngine proposals, MarketDataPort marketData, StakingService staking, WatchCapitalService capital,
            WatchExposureQueries history, WatchExecutionService execution, ExchangeAvailability exchange,
            @Value("${stellartrack.betfair.staleness.market-book-max-age}") Duration marketBookMaxAge, Clock clock) {
        this.configurations = configurations;
        this.sessionConfigurations = sessionConfigurations;
        this.proposals = proposals;
        this.marketData = marketData;
        this.staking = staking;
        this.capital = capital;
        this.history = history;
        this.execution = execution;
        this.exchange = exchange;
        this.marketBookMaxAge = marketBookMaxAge;
        this.clock = clock;
    }

    /** The running configurations of the session, each evaluated independently. */
    public List<WatchSessionConfigurationEntity> running(WatchSessionEntity session) {
        return sessionConfigurations.findBySessionIdAndState(session.getId(), WatchConfigurationState.RUNNING);
    }

    public ConfigurationTick tick(WatchSessionEntity session, WatchSessionConfigurationEntity sessionConfiguration) {
        if (!exchange.isAvailable()) {
            return new ConfigurationTick("Exchange unavailable: " + exchange.unavailableReason().orElse(""), null);
        }
        ResolvedConfiguration configuration = configurations.resolve(sessionConfiguration.getConfigurationId(),
                sessionConfiguration.getConfigurationVersion());
        SelectionStrategy strategy = proposals.resolveStrategy(configuration.settings());
        List<String> unusableLayers = proposals.unusableLayers(strategy);
        if (!unusableLayers.isEmpty()) {
            return new ConfigurationTick("Paused: required input not fresh (" + String.join(", ", unusableLayers) + ")", null);
        }
        Set<String> evaluated = evaluatedMarkets.computeIfAbsent(sessionConfiguration.getId(), id -> ConcurrentHashMap.newKeySet());
        List<MarketSummary> candidates = proposals.candidates(configuration.settings(), clock.instant()).stream()
                .filter(m -> !evaluated.contains(m.marketId())).toList();
        if (candidates.isEmpty()) {
            return new ConfigurationTick(null, null);
        }
        Map<String, MarketPrices> books = marketData.getPrices(candidates.stream().map(MarketSummary::marketId).toList())
                .stream().collect(Collectors.toMap(MarketPrices::marketId, Function.identity(), (a, b) -> a));
        String notice = null;
        for (MarketSummary market : candidates) {
            MarketPrices book = books.get(market.marketId());
            Freshness freshness = book == null ? Freshness.unavailable("Betfair", "No market book")
                    : Freshness.evaluate(book.asOf(), marketBookMaxAge, book.dataDelayed(), book.source().name(), clock);
            if (freshness.state() != Freshness.State.LIVE && freshness.state() != Freshness.State.DELAYED) {
                notice = "Paused entries: market data is " + freshness.state();
                continue;
            }
            evaluated.add(market.marketId());
            String stop = evaluateMarket(session, sessionConfiguration.getId(), configuration, strategy, market, book, freshness);
            if (stop != null) {
                return new ConfigurationTick(notice, stop);
            }
        }
        return new ConfigurationTick(notice, null);
    }

    private String evaluateMarket(WatchSessionEntity session, UUID sessionConfigurationId, ResolvedConfiguration configuration,
            SelectionStrategy strategy, MarketSummary market, MarketPrices book, Freshness freshness) {
        BotConfigurationSettings settings = configuration.settings();
        BigDecimal allocation = settings.capital() == null ? null : settings.capital().allocatedAmount();
        List<ProposalEngine.Proposal> evaluated = proposals.evaluate(configuration, strategy, market, book, freshness,
                (side, price) -> staking.calculateWithBank(side, price, settings.staking(),
                        BankResolver.byBasis(settings.staking(), allocation, () -> capital.view(session).availableToBet()),
                        SequenceOutcome.fromSigns(history.outcomeSignsAscending(sessionConfigurationId))));
        for (ProposalEngine.Proposal proposal : evaluated) {
            WatchExecutionService.Result result = execution.execute(session, sessionConfigurationId, configuration, market,
                    book, proposal);
            if (result.stopReason() != null) {
                return result.stopReason();
            }
        }
        return null;
    }

    /** Drops in-memory progress of configurations that no longer run (session stopped or configuration stopped). */
    public void forget(UUID sessionConfigurationId) {
        evaluatedMarkets.remove(sessionConfigurationId);
    }
}
