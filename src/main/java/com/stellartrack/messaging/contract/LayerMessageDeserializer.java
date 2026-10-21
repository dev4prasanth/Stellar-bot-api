package com.stellartrack.messaging.contract;

import com.stellartrack.messaging.domain.InboundMessage;

/** Converts the raw body into the source-specific, versioned payload type. Must use safe deserialization. */
@FunctionalInterface
public interface LayerMessageDeserializer<T> {

    /** @throws ContractViolationException with {@code MALFORMED_PAYLOAD} when the body cannot be read */
    T deserialize(InboundMessage message);
}
