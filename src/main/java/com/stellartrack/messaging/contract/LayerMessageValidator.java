package com.stellartrack.messaging.contract;

import java.util.List;

/** Checks required fields, identifiers, types, ranges and timestamps against the agreed schema. */
@FunctionalInterface
public interface LayerMessageValidator<T> {

    /** @return human-readable violations; empty when the payload is valid */
    List<String> validate(T payload);
}
