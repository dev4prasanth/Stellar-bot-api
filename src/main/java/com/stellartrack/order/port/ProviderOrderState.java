package com.stellartrack.order.port;

import com.stellartrack.common.domain.BetSide;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Provider view of one order's quantities, from polling (listCurrentOrders) or the order stream.
 * Quantities follow Betfair semantics: requested = matched + remaining + lapsed + cancelled + voided.
 */
public record ProviderOrderState(
        String betId,
        String marketId,
        long selectionId,
        BigDecimal handicap,
        BetSide side,
        BigDecimal priceRequested,
        BigDecimal sizeRequested,
        BigDecimal averagePriceMatched,
        BigDecimal sizeMatched,
        BigDecimal sizeRemaining,
        BigDecimal sizeLapsed,
        BigDecimal sizeCancelled,
        BigDecimal sizeVoided,
        boolean executionComplete,
        Instant placedAt,
        Instant matchedAt,
        String persistenceType,
        String orderType,
        String customerOrderRef,
        String customerStrategyRef,
        Source source) {

    public enum Source {
        PLACE,
        POLL,
        STREAM
    }
}
