package com.stellartrack.betfair.error;

import com.stellartrack.common.exception.ExternalServiceException;
import java.util.Set;

/**
 * Normalized Betfair failure. {@link Kind#TIMEOUT} and {@link Kind#NETWORK} are ambiguous for write operations:
 * the request may have been executed, so callers must reconcile rather than assume failure.
 */
public class BetfairApiException extends ExternalServiceException {

    public enum Kind {
        DISABLED,
        SESSION,
        TRANSIENT,
        CLIENT,
        ACCESS,
        TIMEOUT,
        NETWORK,
        PROTOCOL
    }

    private static final Set<String> SESSION_CODES = Set.of("INVALID_SESSION_INFORMATION", "NO_SESSION");
    private static final Set<String> TRANSIENT_CODES = Set.of("TOO_MANY_REQUESTS", "SERVICE_BUSY", "TIMEOUT_ERROR",
            "UNEXPECTED_ERROR");
    private static final Set<String> ACCESS_CODES = Set.of("NO_APP_KEY", "INVALID_APP_KEY", "ACCESS_DENIED",
            "APP_KEY_CREATION_FAILED", "INVALID_SUBSCRIPTION", "INSUFFICIENT_PERMISSIONS");

    private final Kind kind;
    private final String providerErrorCode;

    public BetfairApiException(Kind kind, String providerErrorCode, String message, Throwable cause) {
        super("BETFAIR_" + kind.name(), message, kind == Kind.TRANSIENT || kind == Kind.TIMEOUT || kind == Kind.NETWORK,
                cause);
        this.kind = kind;
        this.providerErrorCode = providerErrorCode;
    }

    public static BetfairApiException fromErrorCode(String errorCode, String operation) {
        Kind kind;
        if (errorCode != null && SESSION_CODES.contains(errorCode)) {
            kind = Kind.SESSION;
        } else if (errorCode != null && TRANSIENT_CODES.contains(errorCode)) {
            kind = Kind.TRANSIENT;
        } else if (errorCode != null && ACCESS_CODES.contains(errorCode)) {
            kind = Kind.ACCESS;
        } else {
            kind = Kind.CLIENT;
        }
        return new BetfairApiException(kind, errorCode, "Betfair " + operation + " failed: " + errorCode, null);
    }

    public static BetfairApiException disabled(String reason) {
        return new BetfairApiException(Kind.DISABLED, null, "Betfair integration unavailable: " + reason, null);
    }

    public Kind kind() {
        return kind;
    }

    public String providerErrorCode() {
        return providerErrorCode;
    }

    /** Whether the request may be repeated for an idempotent read. */
    public boolean retryableRead() {
        return kind == Kind.TRANSIENT || kind == Kind.TIMEOUT || kind == Kind.NETWORK;
    }

    /** Whether a write may have been executed by the provider despite the failure. */
    public boolean ambiguousForWrite() {
        return kind == Kind.TIMEOUT || kind == Kind.NETWORK || kind == Kind.PROTOCOL;
    }
}
