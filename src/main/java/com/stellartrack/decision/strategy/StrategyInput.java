package com.stellartrack.decision.strategy;

import com.stellartrack.configuration.application.ConfigurationService.ResolvedConfiguration;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.market.filter.FilterOutcome;
import java.util.List;
import java.util.Map;

/** Decision-time inputs. Contains nothing that could reveal the race result. */
public record StrategyInput(
        ResolvedConfiguration configuration,
        MarketSummary market,
        MarketPrices prices,
        Map<Long, FilterOutcome> filterOutcomes,
        Map<Long, Integer> priceRanks) {

    public List<MarketSummary.RunnerInfo> runners() {
        return market.runners();
    }
}
