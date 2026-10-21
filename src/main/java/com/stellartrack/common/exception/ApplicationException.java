package com.stellartrack.common.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Base type for expected, client-meaningful failures. Each subtype maps to one HTTP status and a stable
 * machine-readable {@code code}; the message must never contain secrets.
 */
public abstract class ApplicationException extends RuntimeException {

    private final String code;
    private final transient Map<String, Object> details;

    protected ApplicationException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    protected ApplicationException(String code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public abstract HttpStatus status();
}
