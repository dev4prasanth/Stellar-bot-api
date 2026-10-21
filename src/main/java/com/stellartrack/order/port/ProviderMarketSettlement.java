package com.stellartrack.order.port;

import java.math.BigDecimal;
import java.time.Instant;

/** Market-level settled profit and commission (listClearedOrders grouped by MARKET). */
public record ProviderMarketSettlement(String marketId, Instant settledAt, BigDecimal profit, BigDecimal commission) {
}
