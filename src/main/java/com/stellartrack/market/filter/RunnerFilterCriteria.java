package com.stellartrack.market.filter;

import jakarta.validation.Valid;
import java.util.Set;

/**
 * Advanced runner filters. Every field is optional; a null/empty field is inactive. Stored as part of a
 * configuration version and reused by Manual Mode and the market-criteria selection source.
 */
public record RunnerFilterCriteria(
        @Valid NumericRange runnerCount,
        @Valid NumericRange priceRank,
        @Valid NumericRange odds,
        Set<String> raceTypes,
        Set<String> raceClassifications,
        @Valid NumericRange age,
        @Valid NumericRange weight,
        @Valid NumericRange clothNumber,
        @Valid NumericRange stallDraw,
        @Valid NumericRange daysSinceLastRun,
        String formContains,
        @Valid NumericRange officialRating,
        Set<String> sexTypes,
        String equipmentContains,
        @Valid NumericRange numberOfPlaces,
        @Valid NumericRange marketTradedVolume,
        @Valid NumericRange availableLiquidity) {

    public static RunnerFilterCriteria none() {
        return new RunnerFilterCriteria(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }
}
