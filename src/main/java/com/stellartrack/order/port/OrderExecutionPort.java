package com.stellartrack.order.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Order execution and order/settlement queries against the exchange. Implemented by the Betfair adapter. */
public interface OrderExecutionPort {

    /** Never retried internally: an ambiguous transport failure returns {@code UNKNOWN_OUTCOME}. */
    PlacementResult place(PlaceOrderCommand command);

    /** {@code sizeReduction} null cancels the whole remaining unmatched size. */
    CancellationResult cancel(String marketId, String betId, BigDecimal sizeReduction, String customerRef);

    /** Current (unsettled) orders matching any of the given filters; pagination handled internally. */
    List<ProviderOrderState> currentOrders(Set<String> betIds, Set<String> marketIds, Set<String> customerOrderRefs);

    /** Cleared bets of one status (SETTLED, VOIDED, LAPSED, CANCELLED) settled within the range. */
    List<ProviderSettlement> clearedOrders(String betStatus, Instant settledFrom, Instant settledTo, Set<String> betIds);

    /** Market-level settled profit and commission within the range. */
    List<ProviderMarketSettlement> clearedMarkets(Instant settledFrom, Instant settledTo);
}
