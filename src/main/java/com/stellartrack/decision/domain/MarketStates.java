package com.stellartrack.decision.domain;

import com.stellartrack.common.api.Freshness;
import com.stellartrack.market.domain.MarketPrices;
import java.math.BigDecimal;

/** Builds the decision-time market state captured in snapshots. */
public final class MarketStates {

    private MarketStates() {
    }

    public static DecisionSnapshot.MarketState of(String marketId, long selectionId, MarketPrices prices,
            BigDecimal proposedPrice, Integer priceRank, Freshness freshness) {
        MarketPrices.RunnerPrices runner = prices == null ? null : prices.runner(selectionId).orElse(null);
        return new DecisionSnapshot.MarketState(
                marketId,
                selectionId,
                prices == null ? null : prices.status(),
                prices != null && prices.inPlay(),
                runner == null ? null : runner.bestBack().map(MarketPrices.PriceLevel::price).orElse(null),
                runner == null ? null : runner.bestLay().map(MarketPrices.PriceLevel::price).orElse(null),
                proposedPrice,
                prices == null ? null : prices.asOf(),
                freshness == null ? null : freshness.state().name(),
                prices != null && prices.dataDelayed(),
                priceRank,
                prices == null ? null : prices.totalMatched());
    }
}
