package com.stellartrack.common.api;

/** Versioned API path prefixes shared by controllers and security rules. */
public final class ApiPaths {

    public static final String API_V1 = "/api/v1";
    public static final String AUTH = API_V1 + "/auth";
    public static final String SYSTEM = API_V1 + "/system";
    public static final String AUDIT = API_V1 + "/audit-events";
    public static final String INGESTION = API_V1 + "/ingestion";
    public static final String MARKETS = API_V1 + "/markets";
    public static final String CAPITAL = API_V1 + "/capital";
    public static final String CONFIGURATIONS = API_V1 + "/configurations";
    public static final String STAKING = API_V1 + "/staking";
    public static final String RECOMMENDATIONS = API_V1 + "/recommendations";
    public static final String ORDERS = API_V1 + "/orders";
    public static final String AUTOMATION = API_V1 + "/automation";
    public static final String REPORTS = API_V1 + "/reports";
    public static final String DASHBOARD = API_V1 + "/dashboard";
    public static final String REPLAY = API_V1 + "/replay-jobs";
    public static final String EVENTS = API_V1 + "/events";
    public static final String SIGNALS = API_V1 + "/signals";
    public static final String WATCH = API_V1 + "/watch";

    private ApiPaths() {
    }
}
