package com.stellartrack.order.domain;

import java.util.Set;

/**
 * Submission lifecycle of an order intention, kept separate from matching (bet_order) and settlement.
 * Administrator rejection is a decision status and never appears here; EXCHANGE_REJECTED is Betfair refusing it.
 */
public enum OrderIntentStatus {
    PENDING_SUBMISSION,
    SUBMITTING,
    PLACED,
    EXCHANGE_REJECTED,
    UNKNOWN_OUTCOME,
    TECHNICAL_FAILURE,
    NOT_FOUND_AFTER_RECONCILIATION;

    /** Statuses that hold the runner/side entry slot (see uq_order_intent_live_entry). */
    public static final Set<OrderIntentStatus> LIVE = Set.of(PENDING_SUBMISSION, SUBMITTING, PLACED, UNKNOWN_OUTCOME);
}
