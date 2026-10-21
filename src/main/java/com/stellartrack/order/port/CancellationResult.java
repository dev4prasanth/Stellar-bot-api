package com.stellartrack.order.port;

import java.math.BigDecimal;
import java.time.Instant;

public record CancellationResult(Outcome outcome, BigDecimal sizeCancelled, String errorCode, Instant cancelledAt) {

    public enum Outcome {
        SUCCESS,
        FAILURE,
        UNKNOWN_OUTCOME
    }
}
