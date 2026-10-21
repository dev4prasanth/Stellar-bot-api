package com.stellartrack.decision.domain;

/** Approved taxonomy of administrator rejection reasons (see docs/reporting/rejection-reasons.md). */
public enum RejectionReason {
    PRICE_UNATTRACTIVE("Offered price is not attractive enough", false),
    INSUFFICIENT_CONFIDENCE("Not confident in the selection", false),
    CONFLICTING_INFORMATION("Conflicting information from other sources", false),
    MARKET_CONDITIONS("Market conditions (liquidity, movement, suspension risk)", false),
    RISK_LIMIT_CONCERN("Exposure or risk concern", false),
    DATA_QUALITY_CONCERN("Input data looks wrong, stale or incomplete", false),
    RACE_CHANGE("Non-runner, going change or other race change", false),
    OTHER("Other (note required)", true);

    private final String description;
    private final boolean noteRequired;

    RejectionReason(String description, boolean noteRequired) {
        this.description = description;
        this.noteRequired = noteRequired;
    }

    public String description() {
        return description;
    }

    public boolean noteRequired() {
        return noteRequired;
    }
}
