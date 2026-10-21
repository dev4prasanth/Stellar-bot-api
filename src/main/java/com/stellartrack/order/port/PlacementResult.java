package com.stellartrack.order.port;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Normalized outcome of a placement request. {@code UNKNOWN_OUTCOME} means the provider may or may not
 * have accepted the order (timeout, connection reset, TIMEOUT status) and the intent must be reconciled.
 */
public record PlacementResult(
        Outcome outcome,
        String providerStatus,
        String providerErrorCode,
        String instructionStatus,
        String instructionErrorCode,
        String betId,
        Instant placedAt,
        BigDecimal averagePriceMatched,
        BigDecimal sizeMatched,
        String orderStatus,
        String detail) {

    public enum Outcome {
        PLACED,
        EXCHANGE_REJECTED,
        UNKNOWN_OUTCOME,
        TECHNICAL_FAILURE
    }

    public static PlacementResult unknown(String detail) {
        return new PlacementResult(Outcome.UNKNOWN_OUTCOME, null, null, null, null, null, null, null, null, null, detail);
    }

    public static PlacementResult technicalFailure(String errorCode, String detail) {
        return new PlacementResult(Outcome.TECHNICAL_FAILURE, null, errorCode, null, null, null, null, null, null, null, detail);
    }
}
