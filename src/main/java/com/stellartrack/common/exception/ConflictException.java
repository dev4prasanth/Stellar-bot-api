package com.stellartrack.common.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** The request conflicts with current state (duplicate, concurrent modification, wrong lifecycle state). */
public class ConflictException extends ApplicationException {

    public ConflictException(String code, String message) {
        super(code, message, Map.of());
    }

    public ConflictException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
