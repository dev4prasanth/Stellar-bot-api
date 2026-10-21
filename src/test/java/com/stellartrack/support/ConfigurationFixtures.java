package com.stellartrack.support;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** TEST-ONLY configuration settings as sent to POST /api/v1/configurations. */
public final class ConfigurationFixtures {

    private ConfigurationFixtures() {
    }

    /** Market-criteria BACK on WIN markets, one selection per race (the favourite), fixed stake. */
    public static Map<String, Object> marketCriteriaBack(String fixedStake) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("selection", Map.of("source", "MARKET_CRITERIA", "maxSelectionsPerRace", 1));
        settings.put("market", Map.of("marketType", "WIN", "side", "BACK"));
        settings.put("staking", Map.of("method", "FIXED_STAKE", "amount", new BigDecimal(fixedStake)));
        settings.put("timing", Map.of("entryWindowStartMinutesBefore", 60, "entryWindowEndMinutesBefore", 1,
                "allowInPlay", false, "maxInPlayBetDelaySeconds", 0, "persistenceType", "LAPSE"));
        settings.put("price", Map.of("minOdds", 1.5, "maxOdds", 50, "maxPriceMoveTicks", 2));
        settings.put("limits", Map.of("maxEntriesPerRace", 1, "maxEntriesPerDay", 50, "maxEntriesPerSession", 20,
                "maxExposurePerRace", 100, "maxTotalOpenExposure", 500, "minAvailableBalance", 10));
        settings.put("stops", Map.of());
        settings.put("trading", Map.of("dutching", false, "priceMovementDetection", false, "equalisation", false,
                "closeOut", false, "freeBetPositioning", false));
        settings.put("approval", Map.of("manualApprovalRequired", false, "approvalTimeoutSeconds", 600));
        return settings;
    }
}
