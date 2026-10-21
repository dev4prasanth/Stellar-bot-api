package com.stellartrack.common.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class NotFoundException extends ApplicationException {

    public NotFoundException(String entity, Object id) {
        super("NOT_FOUND", entity + " not found", Map.of("entity", entity, "id", String.valueOf(id)));
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
