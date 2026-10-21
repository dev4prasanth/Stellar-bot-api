package com.stellartrack.messaging.listener;

/** Headers owned by this application. Source contract headers are read by the layer contract adapters. */
final class MessageHeaders {

    static final String ATTEMPT = "x-st-attempt";
    static final String RECEIPT_ID = "x-st-receipt-id";
    static final String ERROR_CLASS = "x-st-error-class";
    static final String CORRELATION_ID = "x-correlation-id";
    /** Where the producer originally published the message; retries re-enter through the default exchange. */
    static final String ORIGINAL_EXCHANGE = "x-st-original-exchange";
    static final String ORIGINAL_ROUTING_KEY = "x-st-original-routing-key";

    private MessageHeaders() {
    }
}
