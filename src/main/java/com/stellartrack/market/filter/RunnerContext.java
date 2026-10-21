package com.stellartrack.market.filter;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.market.domain.MarketPrices;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a filter rule may read about one runner at evaluation time. Accessors return empty when the
 * provider did not supply the value — callers must treat empty as "unavailable", never as a pass.
 */
public record RunnerContext(
        long selectionId,
        String raceType,
        Map<String, String> metadata,
        MarketPrices prices,
        MarketPrices.RunnerPrices runnerPrices,
        Integer priceRank,
        BetSide side) {

    public Optional<String> metadataValue(String key) {
        String value = metadata == null ? null : metadata.get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    public Optional<BigDecimal> metadataNumber(String key) {
        return metadataValue(key).flatMap(RunnerContext::parseDecimal);
    }

    /** Best immediately available price for the bet side: availableToBack for BACK, availableToLay for LAY. */
    public Optional<MarketPrices.PriceLevel> bestPriceForSide() {
        if (runnerPrices == null) {
            return Optional.empty();
        }
        return side == BetSide.LAY ? runnerPrices.bestLay() : runnerPrices.bestBack();
    }

    private static Optional<BigDecimal> parseDecimal(String raw) {
        try {
            return Optional.of(new BigDecimal(raw));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
