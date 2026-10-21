package com.stellartrack.order.domain;

import java.util.UUID;

/**
 * Provider references derived from the intent id. customerRef (max 32 chars) de-duplicates the placeOrders
 * request at Betfair; customerOrderRef (max 32 chars) lets the order be found again after an ambiguous outcome.
 */
public final class OrderReferences {

    private OrderReferences() {
    }

    public static String customerRef(UUID intentId) {
        return intentId.toString().replace("-", "");
    }

    public static String customerOrderRef(UUID intentId) {
        return "ST" + intentId.toString().replace("-", "").substring(0, 30);
    }
}
