package com.stellartrack.market.filter;

import java.util.List;

/** Result of evaluating all active rules for one runner, with an explanation per failed rule. */
public record FilterOutcome(long selectionId, boolean eligible, List<RuleResult> results) {

    public enum Status {
        PASSED,
        FAILED,
        DATA_UNAVAILABLE
    }

    public record RuleResult(FilterField field, Status status, String observedValue, String requirement, String explanation) {
    }

    public List<RuleResult> failures() {
        return results.stream().filter(r -> r.status() != Status.PASSED).toList();
    }
}
