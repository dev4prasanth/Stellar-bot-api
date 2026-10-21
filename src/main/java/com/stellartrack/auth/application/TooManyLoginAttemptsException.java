package com.stellartrack.auth.application;

import com.stellartrack.common.exception.ApplicationException;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class TooManyLoginAttemptsException extends ApplicationException {

    public TooManyLoginAttemptsException() {
        super("TOO_MANY_ATTEMPTS", "Too many sign-in attempts. Wait a minute and try again.", Map.of());
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.TOO_MANY_REQUESTS;
    }
}
