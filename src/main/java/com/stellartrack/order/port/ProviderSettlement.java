package com.stellartrack.order.port;

import com.stellartrack.common.domain.BetSide;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Authoritative bet-level settlement from listClearedOrders. {@code profit} is the provider's settled
 * profit/loss for the bet before market-level commission (commission is only reported at market grain).
 */
public record ProviderSettlement(
        String betId,
        String marketId,
        long selectionId,
        BetSide side,
        String betStatus,
        String betOutcome,
        Instant settledAt,
        BigDecimal priceRequested,
        BigDecimal priceMatched,
        Boolean priceReduced,
        BigDecimal sizeSettled,
        BigDecimal sizeCancelled,
        BigDecimal profit,
        Instant lastMatchedAt,
        Instant placedAt,
        String customerOrderRef,
        String customerStrategyRef) {
}
