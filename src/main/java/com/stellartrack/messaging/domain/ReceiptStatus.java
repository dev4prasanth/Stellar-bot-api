package com.stellartrack.messaging.domain;

/** Traceable processing outcome of one delivery. Values match the message_receipt.status check constraint. */
public enum ReceiptStatus {
    RECEIVED,
    ACCEPTED,
    DUPLICATE,
    STALE,
    INVALID,
    RETRYING,
    QUARANTINED,
    CONTRACT_PENDING,
    REPROCESSED;

    public boolean isReprocessable() {
        return this == QUARANTINED || this == CONTRACT_PENDING || this == INVALID;
    }
}
