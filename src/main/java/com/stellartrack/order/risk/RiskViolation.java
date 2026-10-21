package com.stellartrack.order.risk;

/**
 * A failed pre-trade check.
 *
 * @param requiresReconfirmation true when the administrator can resolve it by reviewing and confirming again
 *                               (e.g. the price moved beyond the approved bounds)
 */
public record RiskViolation(String rule, String code, String message, boolean requiresReconfirmation) {

    public static RiskViolation of(String rule, String code, String message) {
        return new RiskViolation(rule, code, message, false);
    }

    public static RiskViolation reconfirm(String rule, String code, String message) {
        return new RiskViolation(rule, code, message, true);
    }
}
