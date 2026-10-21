package com.stellartrack.order.port;

import com.stellartrack.common.domain.BetSide;
import java.math.BigDecimal;

/**
 * A single limit order instruction.
 *
 * @param customerRef      provider-side de-duplication key for the request (unique per intent)
 * @param customerOrderRef stable reference used to find the order again after an ambiguous outcome
 * @param marketVersion    optional: rejects the order if the market changed (e.g. a non-runner was removed)
 */
public record PlaceOrderCommand(
        String marketId,
        long selectionId,
        BigDecimal handicap,
        BetSide side,
        BigDecimal price,
        BigDecimal size,
        PersistenceType persistenceType,
        String customerRef,
        String customerOrderRef,
        String customerStrategyRef,
        Long marketVersion) {
}
