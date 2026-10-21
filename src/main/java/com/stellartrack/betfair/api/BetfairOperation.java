package com.stellartrack.betfair.api;

/**
 * Betfair JSON-RPC operations used by the application, with the endpoint each belongs to and whether a
 * failed call may be retried safely. Placement and cancellation are never retried after a transport failure.
 */
public enum BetfairOperation {
    LIST_EVENTS(Api.BETTING, "SportsAPING/v1.0/listEvents", true),
    LIST_MARKET_CATALOGUE(Api.BETTING, "SportsAPING/v1.0/listMarketCatalogue", true),
    LIST_MARKET_BOOK(Api.BETTING, "SportsAPING/v1.0/listMarketBook", true),
    PLACE_ORDERS(Api.BETTING, "SportsAPING/v1.0/placeOrders", false),
    CANCEL_ORDERS(Api.BETTING, "SportsAPING/v1.0/cancelOrders", false),
    LIST_CURRENT_ORDERS(Api.BETTING, "SportsAPING/v1.0/listCurrentOrders", true),
    LIST_CLEARED_ORDERS(Api.BETTING, "SportsAPING/v1.0/listClearedOrders", true),
    GET_ACCOUNT_FUNDS(Api.ACCOUNT, "AccountAPING/v1.0/getAccountFunds", true),
    GET_ACCOUNT_DETAILS(Api.ACCOUNT, "AccountAPING/v1.0/getAccountDetails", true),
    GET_ACCOUNT_STATEMENT(Api.ACCOUNT, "AccountAPING/v1.0/getAccountStatement", true);

    public enum Api {
        BETTING,
        ACCOUNT
    }

    private final Api api;
    private final String method;
    private final boolean safeToRetry;

    BetfairOperation(Api api, String method, boolean safeToRetry) {
        this.api = api;
        this.method = method;
        this.safeToRetry = safeToRetry;
    }

    public Api api() {
        return api;
    }

    public String method() {
        return method;
    }

    public boolean safeToRetry() {
        return safeToRetry;
    }
}
