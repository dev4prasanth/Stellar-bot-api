package com.stellartrack.messaging.domain;

import java.util.UUID;

/**
 * Published after a validated layer record has been durably stored. It is an input for approved evaluation
 * rules — never a permission to place a bet.
 */
public record LayerRecordAcceptedEvent(Layer layer, UUID recordId, UUID receiptId, String dedupKey) {
}
