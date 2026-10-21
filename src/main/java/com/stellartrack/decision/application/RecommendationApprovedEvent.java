package com.stellartrack.decision.application;

import java.util.UUID;

/**
 * Published (after commit) when an administrator approves a proposal. The order module then performs full
 * pre-trade revalidation; approval itself never bypasses price, funds, race-state, freshness or risk checks.
 */
public record RecommendationApprovedEvent(UUID recommendationId, String approvedBy) {
}
