package com.stellartrack.messaging.contract;

import com.stellartrack.messaging.domain.ErrorClass;

/** Thrown by contract components when a payload cannot be accepted. The detail must not echo payload secrets. */
public class ContractViolationException extends RuntimeException {

    private final ErrorClass errorClass;

    public ContractViolationException(ErrorClass errorClass, String detail) {
        super(detail);
        this.errorClass = errorClass;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }
}
