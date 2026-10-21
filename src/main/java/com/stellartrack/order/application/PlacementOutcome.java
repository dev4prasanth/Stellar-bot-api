package com.stellartrack.order.application;

import com.stellartrack.order.risk.RiskViolation;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Result of a placement request. Administrator rejection is not represented here (a rejected proposal never
 * reaches placement); RISK_REJECTED means the backend refused to submit; EXCHANGE_REJECTED means Betfair refused.
 */
public record PlacementOutcome(
        Status status,
        UUID intentId,
        String betId,
        BigDecimal sizeMatched,
        BigDecimal averagePriceMatched,
        String providerErrorCode,
        String instructionErrorCode,
        List<RiskViolation> violations,
        String message) {

    public enum Status {
        PLACED,
        EXCHANGE_REJECTED,
        UNKNOWN_OUTCOME,
        TECHNICAL_FAILURE,
        RISK_REJECTED,
        RECONFIRMATION_REQUIRED
    }

    public static PlacementOutcome riskRejected(List<RiskViolation> violations) {
        boolean reconfirm = violations.stream().allMatch(RiskViolation::requiresReconfirmation);
        return new PlacementOutcome(reconfirm ? Status.RECONFIRMATION_REQUIRED : Status.RISK_REJECTED, null, null, null,
                null, null, null, List.copyOf(violations),
                reconfirm ? "Order details changed; review and confirm again" : "Order not submitted: pre-trade checks failed");
    }
}
