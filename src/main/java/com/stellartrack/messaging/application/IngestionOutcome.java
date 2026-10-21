package com.stellartrack.messaging.application;

import com.stellartrack.messaging.domain.ErrorClass;
import com.stellartrack.messaging.domain.ReceiptStatus;
import java.util.UUID;

/**
 * Result of running one delivery through the pipeline; drives the broker action (ack, retry, dead-letter).
 *
 * @param receiptId null only when the receipt itself could not be persisted (database unavailable)
 */
public record IngestionOutcome(UUID receiptId, ReceiptStatus status, ErrorClass errorClass, String detail) {

    public enum BrokerAction {
        ACKNOWLEDGE,
        RETRY,
        DEAD_LETTER
    }

    public BrokerAction brokerAction() {
        return switch (status) {
            case ACCEPTED, DUPLICATE, STALE, REPROCESSED -> BrokerAction.ACKNOWLEDGE;
            case RETRYING, RECEIVED -> BrokerAction.RETRY;
            case INVALID, QUARANTINED, CONTRACT_PENDING -> BrokerAction.DEAD_LETTER;
        };
    }
}
