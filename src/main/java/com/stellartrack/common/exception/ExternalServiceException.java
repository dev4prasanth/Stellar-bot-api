package com.stellartrack.common.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** A dependency (Betfair, broker) failed or is unavailable. The message is safe to show to the administrator. */
public class ExternalServiceException extends ApplicationException {

    private final boolean transientFailure;

    public ExternalServiceException(String code, String message, boolean transientFailure, Throwable cause) {
        super(code, message, Map.of("transient", transientFailure), cause);
        this.transientFailure = transientFailure;
    }

    public boolean isTransient() {
        return transientFailure;
    }

    @Override
    public HttpStatus status() {
        return transientFailure ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
    }
}
