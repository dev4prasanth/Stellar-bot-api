package com.stellartrack.messaging.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Broker-independent view of one delivery: the raw body plus safe broker metadata. Headers are copied
 * only as non-secret strings so contract adapters can read version/identity headers if the contract uses them.
 */
public record InboundMessage(
        Layer layer,
        byte[] body,
        String contentType,
        String brokerMessageId,
        String queue,
        String exchange,
        String routingKey,
        boolean redelivered,
        int attempt,
        Map<String, String> headers,
        Instant receivedAt,
        UUID reprocessedFrom) {

    public InboundMessage {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public String header(String name) {
        return headers.get(name);
    }
}
