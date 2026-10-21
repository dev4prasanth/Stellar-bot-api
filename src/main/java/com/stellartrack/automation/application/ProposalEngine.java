package com.stellartrack.automation.application;

import com.stellartrack.common.api.Freshness;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.configuration.application.ConfigurationService.ResolvedConfiguration;
import com.stellartrack.configuration.domain.BotConfigurationSettings;
import com.stellartrack.decision.domain.DecisionSnapshot;
import com.stellartrack.decision.domain.MarketStates;
import com.stellartrack.decision.strategy.SelectionStrategy;
import com.stellartrack.decision.strategy.StrategyEvaluation;
import com.stellartrack.decision.strategy.StrategyInput;
import com.stellartrack.decision.strategy.StrategyRegistry;
import com.stellartrack.market.application.MarketCatalogService;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.market.filter.FilterOutcome;
import com.stellartrack.market.filter.RunnerFilterEvaluator;
import com.stellartrack.messaging.application.LayerFeedMonitor;
import com.stellartrack.staking.domain.StakeCalculation;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The decision logic shared by Live automation and Watch Mode: candidate markets in the entry window, runner filters,
 * the selection strategy (Layer 1/2/3 inputs), the price on the configured side, the stake and the immutable
 * decision snapshot. It records nothing and places nothing; callers decide what happens to each proposal.
 */
@Component
public class ProposalEngine {

    private static final Duration IN_PLAY_LOOKBACK = Duration.ofMinutes(30);

    /** Calculates the stake for a recommended runner at the proposed price. */
    @FunctionalInterface
    public interface StakeCalculator {
        StakeCalculation calculate(BetSide side, BigDecimal price);
    }

    /**
     * One evaluated runner.
     *
     * @param recommend true when the strategy recommends it and a price and valid stake exist
     * @param reasons   strategy reasons plus price/stake reasons when not recommended
     */
    public record Proposal(long selectionId, BetSide side, BigDecimal price, Integer priceRank, StakeCalculation stake,
            boolean recommend, List<String> reasons, DecisionSnapshot snapshot) {
    }

    private final MarketCatalogService catalog;
    private final RunnerFilterEvaluator filterEvaluator;
    private final StrategyRegistry strategies;
    private final LayerFeedMonitor feeds;

    public ProposalEngine(MarketCatalogService catalog, RunnerFilterEvaluator filterEvaluator,
            StrategyRegistry strategies, LayerFeedMonitor feeds) {
        this.catalog = catalog;
        this.filterEvaluator = filterEvaluator;
        this.strategies = strategies;
        this.feeds = feeds;
    }

    /** The configured selection strategy; configurations that need an administrator cannot run unattended. */
    public SelectionStrategy resolveStrategy(BotConfigurationSettings settings) {
        return switch (settings.selection().source()) {
            case MARKET_CRITERIA -> strategies.marketCriteria();
            case PREDICTION -> strategies.requirePrediction(settings.selection().strategyCode());
            case ADMIN_SELECTED -> throw new IllegalStateException("ADMIN_SELECTED configurations cannot run automatically");
        };
    }

    /** Required layer feeds that are not usable right now, as "LAYER STATE" labels (empty when all are usable). */
    public List<String> unusableLayers(SelectionStrategy strategy) {
        return strategy.requiredLayers().stream().map(feeds::status)
                .filter(s -> !s.isUsable()).map(s -> s.layer() + " " + s.state()).toList();
    }

    /** Markets of the configured type, countries and venues whose start lies in the configuration's entry window. */
    public List<MarketSummary> candidates(BotConfigurationSettings settings, Instant now) {
        BotConfigurationSettings.Timing timing = settings.timing();
        Instant from = timing.allowInPlay() ? now.minus(IN_PLAY_LOOKBACK)
                : now.plus(Duration.ofMinutes(timing.entryWindowEndMinutesBefore()));
        Instant to = now.plus(Duration.ofMinutes(timing.entryWindowStartMinutesBefore()));
        return catalog.candidates(settings.market().marketType(), settings.market().countries(),
                settings.market().venues(), from, to);
    }

    /** Evaluates every runner the strategy reports on, in strategy order. */
    public List<Proposal> evaluate(ResolvedConfiguration configuration, SelectionStrategy strategy, MarketSummary market,
            MarketPrices book, Freshness freshness, StakeCalculator stakes) {
        BotConfigurationSettings settings = configuration.settings();
        BetSide side = settings.market().side();
        Map<Long, FilterOutcome> filters = filterEvaluator.evaluate(market, book, settings.filters(), side).stream()
                .collect(Collectors.toMap(FilterOutcome::selectionId, Function.identity()));
        Map<Long, Integer> ranks = filterEvaluator.priceRanks(book);
        List<StrategyEvaluation> evaluations = strategy.evaluate(new StrategyInput(configuration, market, book, filters, ranks));
        List<Proposal> proposals = new ArrayList<>();
        for (StrategyEvaluation evaluation : evaluations) {
            Integer rank = ranks.get(evaluation.selectionId());
            BigDecimal price = book.runner(evaluation.selectionId())
                    .flatMap(r -> side == BetSide.BACK ? r.bestBack() : r.bestLay())
                    .map(MarketPrices.PriceLevel::price).orElse(null);
            List<String> reasons = new ArrayList<>(evaluation.reasons());
            StakeCalculation stake = null;
            boolean recommend = evaluation.recommend();
            if (recommend && price == null) {
                recommend = false;
                reasons.add("No price available on the " + side + " side");
            }
            if (recommend) {
                stake = stakes.calculate(side, price);
                if (market.marketType() == com.stellartrack.common.domain.MarketType.EACH_WAY) {
                    stake = stake.forEachWay(market.eachWayDivisor());
                }
                if (!stake.valid()) {
                    recommend = false;
                    reasons.add("Stake: " + stake.rejectionReason());
                }
            }
            DecisionSnapshot snapshot = new DecisionSnapshot(strategy.code(), strategy.version(), strategy.formulaReference(),
                    evaluation.layerRefs(), evaluation.inputs(), evaluation.intermediate(), evaluation.gates(),
                    evaluation.flags(), evaluation.finalScore(), recommend ? "RECOMMENDED" : "NO_BET", reasons, stake,
                    MarketStates.of(market.marketId(), evaluation.selectionId(), book, price, rank, freshness));
            proposals.add(new Proposal(evaluation.selectionId(), side, price, rank, stake, recommend, List.copyOf(reasons),
                    snapshot));
        }
        return proposals;
    }
}
