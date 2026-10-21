package com.stellartrack.order.application;

import com.stellartrack.common.api.Freshness;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.common.domain.OperatingMode;
import com.stellartrack.common.exception.BusinessRuleException;
import com.stellartrack.common.exception.ConflictException;
import com.stellartrack.configuration.application.ConfigurationService;
import com.stellartrack.configuration.application.ConfigurationService.ResolvedConfiguration;
import com.stellartrack.configuration.domain.BotConfigurationSettings.SelectionSourceType;
import com.stellartrack.decision.application.DecisionService;
import com.stellartrack.decision.application.NewDecision;
import com.stellartrack.decision.domain.DecisionSnapshot;
import com.stellartrack.decision.domain.MarketStates;
import com.stellartrack.decision.domain.RecommendationStatus;
import com.stellartrack.decision.persistence.RecommendationEntity;
import com.stellartrack.market.application.MarketCatalogService;
import com.stellartrack.market.application.MarketQueryService;
import com.stellartrack.market.application.MarketQueryService.MarketDetail;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.market.filter.RunnerFilterCriteria;
import com.stellartrack.order.control.TradingControlService;
import com.stellartrack.order.domain.OrderPurpose;
import com.stellartrack.order.port.PersistenceType;
import com.stellartrack.order.risk.RiskViolation;
import com.stellartrack.staking.application.StakingService;
import com.stellartrack.staking.application.StakingService.SequenceOutcome;
import com.stellartrack.staking.domain.StakeCalculation;
import com.stellartrack.staking.domain.StakingSettings;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Manual Mode bet ticket: Review Bet → Confirm &amp; Place Bet. The review shows the authoritative stake,
 * liability, estimated profit and every failing pre-trade check; confirmation submits exactly the reviewed
 * terms after full revalidation.
 */
@Service
public class ManualTicketService {

    static final Duration REVIEW_VALIDITY = Duration.ofSeconds(60);
    static final String STRATEGY_CODE = "MANUAL_SELECTION";
    static final String COMMISSION_NOTE = "Betfair charges commission on net winnings per market at your account rate."
            + " It is not included in the estimate.";

    public record TicketRequest(String marketId, long selectionId, BetSide side, BigDecimal price,
            UUID configurationId, StakingSettings staking, PersistenceType persistenceType) {
    }

    public record TicketReview(String marketId, String marketName, String venue, Instant startTime, String marketType,
            long selectionId, String runnerName, BetSide side, BigDecimal price, Freshness freshness,
            BigDecimal bestBack, BigDecimal bestLay, StakeCalculation stake, String commissionNote,
            List<RiskViolation> violations, String reviewToken, Instant reviewExpiresAt) {
    }

    /** {@code staking} is required only for tickets reviewed without a configuration. */
    /** @param dutchGroupId set when this ticket is one leg of a Dutch bet */
    public record ConfirmRequest(String reviewToken, String idempotencyKey, boolean acceptDelayedData,
            StakingSettings staking, UUID dutchGroupId) {

        public ConfirmRequest(String reviewToken, String idempotencyKey, boolean acceptDelayedData, StakingSettings staking) {
            this(reviewToken, idempotencyKey, acceptDelayedData, staking, null);
        }
    }

    private final MarketQueryService marketQuery;
    private final MarketCatalogService catalog;
    private final ConfigurationService configurations;
    private final StakingService staking;
    private final OrderExposureQueries exposure;
    private final OrderPlacementService placement;
    private final DecisionService decisions;
    private final ReviewTokenSigner signer;
    private final TradingControlService tradingControl;
    private final Clock clock;

    public ManualTicketService(MarketQueryService marketQuery, MarketCatalogService catalog, ConfigurationService configurations,
            StakingService staking, OrderExposureQueries exposure, OrderPlacementService placement,
            DecisionService decisions, ReviewTokenSigner signer, TradingControlService tradingControl, Clock clock) {
        this.marketQuery = marketQuery;
        this.catalog = catalog;
        this.configurations = configurations;
        this.staking = staking;
        this.exposure = exposure;
        this.placement = placement;
        this.decisions = decisions;
        this.signer = signer;
        this.tradingControl = tradingControl;
        this.clock = clock;
    }

    public TicketReview review(TicketRequest request, String actor) {
        ResolvedConfiguration configuration = request.configurationId() == null ? null
                : configurations.current(request.configurationId());
        StakingSettings stakingSettings = configuration != null ? configuration.settings().staking() : request.staking();
        if (stakingSettings == null) {
            throw new BusinessRuleException("STAKING_REQUIRED", "Choose a configuration or staking settings");
        }
        PersistenceType persistence = request.persistenceType() != null ? request.persistenceType()
                : configuration != null ? configuration.settings().timing().persistenceType() : PersistenceType.LAPSE;
        MarketDetail detail = marketQuery.detail(request.marketId(), request.side(), RunnerFilterCriteria.none());
        MarketPrices.RunnerPrices runner = detail.prices() == null ? null
                : detail.prices().runner(request.selectionId()).orElse(null);
        BigDecimal price = request.price() != null ? request.price() : bestFor(request.side(), runner);
        if (price == null) {
            throw new BusinessRuleException("NO_PRICE", "No price is available on this side; enter a price");
        }
        StakeCalculation stake = forMarket(staking.calculate(request.side(), price, stakingSettings,
                allocation(configuration), sequence(configuration)), detail.market());
        PlacementCommand preview = command(request, configuration, price, stake, persistence, "preview-" + UUID.randomUUID(),
                actor, false, null, null);
        List<RiskViolation> violations = placement.preview(preview);
        Instant expiresAt = clock.instant().plus(REVIEW_VALIDITY);
        String token = stake.valid() ? signer.sign(new ReviewTokenSigner.ReviewedTerms(request.marketId(),
                request.selectionId(), request.side().name(), price, stake.stake(),
                configuration == null ? null : configuration.id(), configuration == null ? null : configuration.version(),
                persistence.name(), expiresAt)) : null;
        String runnerName = detail.market().runners().stream().filter(r -> r.selectionId() == request.selectionId())
                .findFirst().map(r -> r.name()).orElse(String.valueOf(request.selectionId()));
        return new TicketReview(request.marketId(), detail.market().marketName(), detail.market().venue(),
                detail.market().startTime(), detail.market().marketType().name(), request.selectionId(), runnerName,
                request.side(), price, detail.freshness(),
                runner == null ? null : runner.bestBack().map(MarketPrices.PriceLevel::price).orElse(null),
                runner == null ? null : runner.bestLay().map(MarketPrices.PriceLevel::price).orElse(null),
                stake, COMMISSION_NOTE, violations, token, token == null ? null : expiresAt);
    }

    /** Each-Way markets stake per part: the money at risk and the estimated profit cover both parts. */
    private static StakeCalculation forMarket(StakeCalculation stake, MarketSummary market) {
        return market.marketType() == MarketType.EACH_WAY ? stake.forEachWay(market.eachWayDivisor()) : stake;
    }

    /** Places exactly the reviewed terms. The stake is recomputed and must still match, otherwise reconfirm. */
    public PlacementOutcome confirm(ConfirmRequest request, String actor) {
        if (tradingControl.isWatchActive()) {
            throw new ConflictException("WATCH_MODE_ACTIVE", "Watch Mode is running: real betting is paused");
        }
        ReviewTokenSigner.ReviewedTerms terms = signer.verify(request.reviewToken());
        ResolvedConfiguration configuration = terms.configurationId() == null ? null
                : configurations.resolve(terms.configurationId(), terms.configurationVersion());
        StakingSettings stakingSettings = configuration != null ? configuration.settings().staking() : request.staking();
        if (stakingSettings == null) {
            throw new BusinessRuleException("STAKING_REQUIRED", "Staking settings are required");
        }
        BetSide side = BetSide.valueOf(terms.side());
        StakeCalculation stake = forMarket(staking.calculate(side, terms.price(), stakingSettings,
                allocation(configuration), sequence(configuration)), catalog.require(terms.marketId()));
        if (!stake.valid() || stake.stake().compareTo(terms.stake()) != 0) {
            return PlacementOutcome.riskRejected(List.of(RiskViolation.reconfirm("STAKE", "STAKE_CHANGED",
                    "The calculated stake changed since review (" + terms.stake() + " → "
                            + (stake.valid() ? stake.stake() : stake.rejectionReason()) + ")")));
        }
        TicketRequest ticket = new TicketRequest(terms.marketId(), terms.selectionId(), side, terms.price(),
                terms.configurationId(), stakingSettings, PersistenceType.valueOf(terms.persistenceType()));
        RecommendationEntity recommendation = recordSelection(ticket, configuration, stake);
        PlacementCommand command = command(ticket, configuration, terms.price(), stake,
                PersistenceType.valueOf(terms.persistenceType()), request.idempotencyKey(), actor,
                request.acceptDelayedData(), recommendation.getId(), request.dutchGroupId());
        PlacementOutcome outcome = placement.submit(command);
        if (outcome.status() == PlacementOutcome.Status.RISK_REJECTED
                || outcome.status() == PlacementOutcome.Status.RECONFIRMATION_REQUIRED) {
            decisions.noteNotExecuted(recommendation.getId(), outcome.violations().stream()
                    .map(RiskViolation::message).reduce((a, b) -> a + "; " + b).orElse("Not submitted"));
        }
        return outcome;
    }

    /** The administrator's own selection is recorded as an ADMIN_SELECTED decision for full traceability. */
    private RecommendationEntity recordSelection(TicketRequest ticket, ResolvedConfiguration configuration,
            StakeCalculation stake) {
        MarketDetail detail = marketQuery.detail(ticket.marketId(), ticket.side(), RunnerFilterCriteria.none());
        Integer rank = detail.runners().stream().filter(r -> r.selectionId() == ticket.selectionId()).findFirst()
                .map(MarketQueryService.RunnerView::priceRank).orElse(null);
        DecisionSnapshot snapshot = new DecisionSnapshot(STRATEGY_CODE, "1.0", "Administrator-selected runner (race-market ticket)",
                List.of(), List.of(new DecisionSnapshot.Factor("priceRank", rank == null ? null : rank.toString(), "rank",
                        "Derived from Betfair best back prices", null, null)),
                List.of(), List.of(), List.of(), null, "SELECTED", List.of("Selected by the administrator"), stake,
                MarketStates.of(ticket.marketId(), ticket.selectionId(), detail.prices(), ticket.price(), rank,
                        detail.freshness()));
        return decisions.record(new NewDecision(configuration == null ? null : configuration.id(),
                configuration == null ? null : configuration.version(), OperatingMode.MANUAL,
                SelectionSourceType.ADMIN_SELECTED, ticket.marketId(), ticket.selectionId(), ticket.side(),
                detail.market().marketType(), RecommendationStatus.ACCEPTED, false, null, null, snapshot));
    }

    private PlacementCommand command(TicketRequest request, ResolvedConfiguration configuration, BigDecimal price,
            StakeCalculation stake, PersistenceType persistence, String idempotencyKey, String actor,
            boolean acceptDelayed, UUID recommendationId, UUID dutchGroupId) {
        return new PlacementCommand(OperatingMode.MANUAL, OperatingMode.MANUAL, OrderPurpose.ENTRY, null, recommendationId, configuration,
                request.marketId(), request.selectionId(), request.side(), price, stake, persistence, idempotencyKey,
                actor, price, acceptDelayed, null, dutchGroupId);
    }

    private BigDecimal allocation(ResolvedConfiguration configuration) {
        return configuration == null || configuration.settings().capital() == null ? null
                : configuration.settings().capital().allocatedAmount();
    }

    private List<SequenceOutcome> sequence(ResolvedConfiguration configuration) {
        if (configuration == null) {
            return List.of();
        }
        return SequenceOutcome.fromSigns(exposure.outcomeSignsAscending(configuration.id()));
    }

    private static BigDecimal bestFor(BetSide side, MarketPrices.RunnerPrices runner) {
        if (runner == null) {
            return null;
        }
        return (side == BetSide.BACK ? runner.bestBack() : runner.bestLay()).map(MarketPrices.PriceLevel::price).orElse(null);
    }
}
