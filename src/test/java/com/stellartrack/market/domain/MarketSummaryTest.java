package com.stellartrack.market.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketSummaryTest {

    @Test
    void runnerMetadataWithNullValuesFromBetfairKeepsOnlySuppliedValues() {
        // Betfair RUNNER_METADATA sends keys with null values for data it does not hold.
        Map<String, String> metadata = new HashMap<>();
        metadata.put("AGE", "5");
        metadata.put("FORM", null);
        metadata.put("COLOURS_FILENAME", null);

        MarketSummary.RunnerInfo runner = new MarketSummary.RunnerInfo(1L, "Runner", BigDecimal.ZERO, 1, metadata);

        assertThat(runner.metadata()).containsExactly(Map.entry("AGE", "5"));
    }

    @Test
    void missingMetadataIsEmpty() {
        assertThat(new MarketSummary.RunnerInfo(1L, "Runner", BigDecimal.ZERO, 1, null).metadata()).isEmpty();
    }
}
