package com.stellartrack.auth.application;

import com.stellartrack.common.exception.ApplicationException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Deliberately generic so responses never reveal whether a username exists or an account is locked. */
public class AuthenticationFailedException extends ApplicationException {

    public AuthenticationFailedException(String code, String message) {
        super(code, message, Map.of());
    }

    public static AuthenticationFailedException invalidCredentials() {
        return new AuthenticationFailedException("INVALID_CREDENTIALS", "Invalid username or password.");
    }

    public static AuthenticationFailedException sessionExpired() {
        return new AuthenticationFailedException("SESSION_EXPIRED", "Your session has expired. Please sign in again.");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNAUTHORIZED;
    }
}
