package com.stellartrack.common.exception;

import com.stellartrack.common.capability.Capability;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The requested capability cannot run because a contract, credential or approved business rule is missing.
 * Raised instead of producing fabricated behaviour.
 */
public class CapabilityBlockedException extends ApplicationException {

    public CapabilityBlockedException(Capability capability, String reason) {
        super("CAPABILITY_BLOCKED", capability.label() + " is blocked: " + reason,
                Map.of("capability", capability.name(), "reason", reason));
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
