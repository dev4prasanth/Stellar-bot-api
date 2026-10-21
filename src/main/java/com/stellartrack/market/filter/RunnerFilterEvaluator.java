package com.stellartrack.market.filter;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSummary;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Evaluates {@link RunnerFilterCriteria} against real market data. Only ACTIVE runners can be eligible.
 * Every active rule must pass; missing data fails the rule with an explanation.
 */
@Component
public class RunnerFilterEvaluator {

    public List<FilterOutcome> evaluate(MarketSummary market, MarketPrices prices, RunnerFilterCriteria criteria,
            BetSide side) {
        List<RunnerFilterRule> rules = buildRules(criteria == null ? RunnerFilterCriteria.none() : criteria);
        Map<Long, Integer> ranks = priceRanks(prices);
        List<FilterOutcome> outcomes = new ArrayList<>();
        for (MarketSummary.RunnerInfo runner : market.runners()) {
            MarketPrices.RunnerPrices runnerPrices = prices == null ? null : prices.runner(runner.selectionId()).orElse(null);
            RunnerContext context = new RunnerContext(runner.selectionId(), market.raceType(), runner.metadata(), prices,
                    runnerPrices, ranks.get(runner.selectionId()), side);
            List<FilterOutcome.RuleResult> results = new ArrayList<>();
            if (runnerPrices == null || !runnerPrices.isActive()) {
                results.add(new FilterOutcome.RuleResult(FilterField.RUNNER_COUNT, FilterOutcome.Status.FAILED,
                        runnerPrices == null ? "no price data" : runnerPrices.status(), "ACTIVE runner",
                        "Runner is not active" + (runnerPrices == null ? " (no market book)" : " (" + runnerPrices.status() + ")")));
            }
            rules.forEach(rule -> results.add(rule.evaluate(context)));
            boolean eligible = results.stream().allMatch(r -> r.status() == FilterOutcome.Status.PASSED);
            outcomes.add(new FilterOutcome(runner.selectionId(), eligible, results));
        }
        return outcomes;
    }

    /** Rank 1 = shortest best-back price among active runners (the favourite). Runners without a price are unranked. */
    public Map<Long, Integer> priceRanks(MarketPrices prices) {
        Map<Long, Integer> ranks = new HashMap<>();
        if (prices == null) {
            return ranks;
        }
        List<MarketPrices.RunnerPrices> priced = prices.runners().stream()
                .filter(MarketPrices.RunnerPrices::isActive)
                .filter(r -> r.bestBack().isPresent())
                .sorted(Comparator.comparing(r -> r.bestBack().get().price()))
                .toList();
        for (int i = 0; i < priced.size(); i++) {
            ranks.put(priced.get(i).selectionId(), i + 1);
        }
        return ranks;
    }

    List<RunnerFilterRule> buildRules(RunnerFilterCriteria c) {
        List<RunnerFilterRule> rules = new ArrayList<>();
        addNumeric(rules, FilterField.RUNNER_COUNT, c.runnerCount(),
                ctx -> ctx.prices() == null ? Optional.empty() : Optional.ofNullable(ctx.prices().numberOfActiveRunners()).map(BigDecimal::valueOf));
        addNumeric(rules, FilterField.PRICE_RANK, c.priceRank(),
                ctx -> Optional.ofNullable(ctx.priceRank()).map(BigDecimal::valueOf));
        addNumeric(rules, FilterField.ODDS, c.odds(), ctx -> ctx.bestPriceForSide().map(MarketPrices.PriceLevel::price));
        addOneOf(rules, FilterField.RACE_TYPE, c.raceTypes(), ctx -> Optional.ofNullable(ctx.raceType()));
        addOneOf(rules, FilterField.RACE_CLASSIFICATION, c.raceClassifications(), ctx -> Optional.empty());
        addNumeric(rules, FilterField.AGE, c.age(), ctx -> ctx.metadataNumber("AGE"));
        addNumeric(rules, FilterField.WEIGHT, c.weight(), ctx -> ctx.metadataNumber("WEIGHT_VALUE"));
        addNumeric(rules, FilterField.CLOTH_NUMBER, c.clothNumber(), ctx -> ctx.metadataNumber("CLOTH_NUMBER"));
        addNumeric(rules, FilterField.STALL_DRAW, c.stallDraw(), ctx -> ctx.metadataNumber("STALL_DRAW"));
        addNumeric(rules, FilterField.DAYS_SINCE_LAST_RUN, c.daysSinceLastRun(), ctx -> ctx.metadataNumber("DAYS_SINCE_LAST_RUN"));
        if (notBlank(c.formContains())) {
            rules.add(RunnerFilterRule.contains(FilterField.FORM, c.formContains(), ctx -> ctx.metadataValue("FORM")));
        }
        addNumeric(rules, FilterField.OFFICIAL_RATING, c.officialRating(), ctx -> ctx.metadataNumber("OFFICIAL_RATING"));
        addOneOf(rules, FilterField.SEX, c.sexTypes(), ctx -> ctx.metadataValue("SEX_TYPE"));
        if (notBlank(c.equipmentContains())) {
            rules.add(RunnerFilterRule.contains(FilterField.EQUIPMENT, c.equipmentContains(), ctx -> ctx.metadataValue("WEARING")));
        }
        addNumeric(rules, FilterField.NUMBER_OF_PLACES, c.numberOfPlaces(),
                ctx -> ctx.prices() == null ? Optional.empty() : Optional.ofNullable(ctx.prices().numberOfWinners()).map(BigDecimal::valueOf));
        addNumeric(rules, FilterField.MARKET_TRADED_VOLUME, c.marketTradedVolume(),
                ctx -> ctx.prices() == null ? Optional.empty() : Optional.ofNullable(ctx.prices().totalMatched()));
        addNumeric(rules, FilterField.AVAILABLE_LIQUIDITY, c.availableLiquidity(),
                ctx -> ctx.bestPriceForSide().map(MarketPrices.PriceLevel::size));
        return rules;
    }

    private static void addNumeric(List<RunnerFilterRule> rules, FilterField field, NumericRange range,
            java.util.function.Function<RunnerContext, Optional<BigDecimal>> extractor) {
        if (range != null && range.isActive()) {
            rules.add(RunnerFilterRule.numeric(field, range, extractor));
        }
    }

    private static void addOneOf(List<RunnerFilterRule> rules, FilterField field, Set<String> allowed,
            java.util.function.Function<RunnerContext, Optional<String>> extractor) {
        if (allowed != null && !allowed.isEmpty()) {
            rules.add(RunnerFilterRule.oneOf(field, allowed, extractor));
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
