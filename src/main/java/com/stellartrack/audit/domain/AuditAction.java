package com.stellartrack.audit.domain;

/** Every audited action. Keeping them in one enum keeps audit vocabulary consistent across modules. */
public enum AuditAction {
    // Authentication
    ADMIN_REGISTERED,
    /** No longer recorded; kept so rows written before the change still load. Hidden from audit search. */
    LOGIN,
    /** No longer recorded; see {@link #LOGIN}. */
    LOGOUT,
    /** No longer recorded; see {@link #LOGIN}. */
    TOKEN_REFRESH,
    /** No longer recorded; see {@link #LOGIN}. */
    REFRESH_TOKEN_REUSE,
    ACCESS_DENIED,

    // Configurations and staking
    CONFIGURATION_CREATED,
    CONFIGURATION_VERSION_CREATED,
    CONFIGURATION_DUPLICATED,
    CONFIGURATION_ARCHIVED,
    STAKING_PLAN_IMPORTED,

    // Automation
    AUTOMATION_STARTED,
    AUTOMATION_STOPPED,
    AUTOMATION_ERROR,
    AUTOMATION_PAUSED_ENTRIES,
    STOP_ALL_ENGAGED,
    STOP_ALL_RELEASED,
    TRADING_MODE_CHANGED,

    // Decisions
    RECOMMENDATION_CREATED,
    RECOMMENDATION_APPROVED,
    RECOMMENDATION_REJECTED,
    RECOMMENDATION_EXPIRED,

    // Orders
    ORDER_RISK_REJECTED,
    ORDER_INTENT_CREATED,
    ORDER_PLACED,
    ORDER_EXCHANGE_REJECTED,
    ORDER_OUTCOME_UNKNOWN,
    ORDER_TECHNICAL_FAILURE,
    ORDER_RECONCILED,
    ORDER_CANCEL_REQUESTED,
    ORDER_CANCEL_COMPLETED,
    SETTLEMENT_RECORDED,
    SETTLEMENT_CORRECTED,

    // Ingestion
    MESSAGE_QUARANTINED,
    MESSAGE_REPROCESS_REQUESTED,

    // Integrations
    BETFAIR_SESSION_OPENED,
    BETFAIR_SESSION_FAILED,

    // Replay
    REPLAY_UPLOADED,
    REPLAY_STATUS_CHANGED,

    // Watch Mode (simulation; never a real bet)
    WATCH_SESSION_STARTED,
    WATCH_SESSION_STOPPED,
    WATCH_BET_PLACED,
    WATCH_BET_RISK_REJECTED,
    WATCH_BET_SETTLED,
    WATCH_BET_REVOKED;

    /** Routine login/token events that are no longer audited; older rows are excluded from audit search. */
    public static final java.util.Set<AuditAction> RETIRED_AUTHENTICATION =
            java.util.EnumSet.of(LOGIN, LOGOUT, TOKEN_REFRESH, REFRESH_TOKEN_REUSE);
}
