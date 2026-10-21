package com.stellartrack.common.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** A syntactically valid request that a business rule (limit, eligibility, risk check) does not allow. */
public class BusinessRuleException extends ApplicationException {

    public BusinessRuleException(String code, String message) {
        super(code, message, Map.of());
    }

    public BusinessRuleException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_CONTENT;
    }
}
