package com.stellartrack.messaging.domain;

/**
 * Non-secret failure classification. Schema/validation problems are permanent; dependency problems are
 * transient and retried; business ineligibility is recorded but is not an ingestion failure.
 */
public enum ErrorClass {
    PAYLOAD_TOO_LARGE(false),
    MALFORMED_PAYLOAD(false),
    UNSUPPORTED_CONTRACT_VERSION(false),
    SCHEMA_VALIDATION(false),
    CONTRACT_PENDING(false),
    STALE_SOURCE_EVENT(false),
    TRANSIENT_DEPENDENCY(true),
    UNEXPECTED(false);

    private final boolean transientFailure;

    ErrorClass(boolean transientFailure) {
        this.transientFailure = transientFailure;
    }

    public boolean isTransient() {
        return transientFailure;
    }
}
