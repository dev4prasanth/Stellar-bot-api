package com.stellartrack.messaging.application;

import com.stellartrack.messaging.config.MessagingProperties;
import com.stellartrack.messaging.contract.ContractViolationException;
import com.stellartrack.messaging.contract.LayerContract;
import com.stellartrack.messaging.contract.LayerContractRegistry;
import com.stellartrack.messaging.domain.ErrorClass;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.LayerRecordAcceptedEvent;
import com.stellartrack.messaging.domain.NormalizedLayerRecord;
import com.stellartrack.messaging.domain.ReceiptStatus;
import com.stellartrack.messaging.health.ConsumerHealthRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * Shared, layer-agnostic ingestion workflow:
 * receipt → contract resolution → deserialize/validate/map → freshness → idempotent persistence → internal event.
 * Broker concerns (ack, retry, dead-letter) stay in the listener; this class never touches a channel.
 * Payloads are never logged — only receipt ids, hashes and error classes.
 */
@Service
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final MessagingProperties properties;
    private final LayerContractRegistry contracts;
    private final IngestionStore store;
    private final ApplicationEventPublisher events;
    private final ConsumerHealthRegistry health;
    private final Clock clock;

    public IngestionPipeline(MessagingProperties properties, LayerContractRegistry contracts, IngestionStore store,
            ApplicationEventPublisher events, ConsumerHealthRegistry health, Clock clock) {
        this.properties = properties;
        this.contracts = contracts;
        this.store = store;
        this.events = events;
        this.health = health;
        this.clock = clock;
    }

    public IngestionOutcome process(InboundMessage message, String correlationId) {
        long started = System.nanoTime();
        UUID receiptId;
        boolean oversized = message.body().length > properties.maxPayloadBytes();
        try {
            receiptId = store.openReceipt(message, properties.storeRawPayload() && !oversized, correlationId);
        } catch (DataAccessException | TransactionException e) {
            log.warn("Cannot persist {} receipt (database unavailable); delivery will be retried", message.layer());
            return record(message, started, new IngestionOutcome(null, ReceiptStatus.RETRYING,
                    ErrorClass.TRANSIENT_DEPENDENCY, "Receipt persistence failed"));
        }

        IngestionOutcome outcome;
        if (oversized) {
            outcome = new IngestionOutcome(receiptId, ReceiptStatus.QUARANTINED, ErrorClass.PAYLOAD_TOO_LARGE,
                    "Payload of " + message.body().length + " bytes exceeds limit of " + properties.maxPayloadBytes());
        } else {
            outcome = evaluate(message, receiptId);
        }
        if (outcome.status() != ReceiptStatus.ACCEPTED && outcome.status() != ReceiptStatus.DUPLICATE) {
            completeQuietly(outcome);
        }
        log.info("Ingestion {} receipt={} status={} errorClass={}", message.layer(), receiptId, outcome.status(),
                outcome.errorClass());
        return record(message, started, outcome);
    }

    private IngestionOutcome evaluate(InboundMessage message, UUID receiptId) {
        try {
            LayerContract<?> contract = contracts.resolve(message);
            NormalizedLayerRecord normalized = contract.apply(message);
            store.identifyReceipt(receiptId, normalized);
            if (isStale(message, normalized)) {
                return new IngestionOutcome(receiptId, ReceiptStatus.STALE, ErrorClass.STALE_SOURCE_EVENT,
                        "Source event older than the configured freshness limit");
            }
            IngestionStore.PersistResult result = store.persistAccepted(receiptId, normalized);
            if (result.status() == ReceiptStatus.ACCEPTED) {
                events.publishEvent(new LayerRecordAcceptedEvent(message.layer(), result.recordId(), receiptId,
                        normalized.dedupKey()));
            }
            return new IngestionOutcome(receiptId, result.status(), null, null);
        } catch (ContractViolationException e) {
            ReceiptStatus status = switch (e.errorClass()) {
                case CONTRACT_PENDING -> ReceiptStatus.CONTRACT_PENDING;
                case STALE_SOURCE_EVENT -> ReceiptStatus.STALE;
                default -> ReceiptStatus.INVALID;
            };
            return new IngestionOutcome(receiptId, status, e.errorClass(), e.getMessage());
        } catch (NonTransientDataAccessException e) {
            log.error("Non-transient persistence failure for {} receipt {}", message.layer(), receiptId, e);
            return new IngestionOutcome(receiptId, ReceiptStatus.QUARANTINED, ErrorClass.UNEXPECTED,
                    "Persistence rejected the record");
        } catch (DataAccessException | TransactionException e) {
            return new IngestionOutcome(receiptId, ReceiptStatus.RETRYING, ErrorClass.TRANSIENT_DEPENDENCY,
                    "Temporary persistence failure");
        } catch (RuntimeException e) {
            log.error("Unexpected ingestion failure for {} receipt {}", message.layer(), receiptId, e);
            return new IngestionOutcome(receiptId, ReceiptStatus.QUARANTINED, ErrorClass.UNEXPECTED,
                    e.getClass().getSimpleName());
        }
    }

    /** Contracts without a source timestamp (Layer 1) are never stale; freshness can be switched off per layer. */
    private boolean isStale(InboundMessage message, NormalizedLayerRecord record) {
        MessagingProperties.LayerBinding binding = properties.binding(message.layer());
        Duration maxAge = binding.freshnessMaxAge();
        return binding.rejectStale() && record.sourceEventAt() != null && maxAge != null
                && record.sourceEventAt().isBefore(clock.instant().minus(maxAge));
    }

    /** Called by the listener once a retry budget is exhausted. */
    public void markRetryExhausted(UUID receiptId, String detail) {
        if (receiptId != null) {
            store.completeReceipt(receiptId, ReceiptStatus.QUARANTINED, ErrorClass.TRANSIENT_DEPENDENCY, detail);
        }
    }

    private void completeQuietly(IngestionOutcome outcome) {
        if (outcome.receiptId() == null) {
            return;
        }
        try {
            store.completeReceipt(outcome.receiptId(), outcome.status(), outcome.errorClass(), outcome.detail());
        } catch (DataAccessException | TransactionException e) {
            log.warn("Could not update receipt {} to {}", outcome.receiptId(), outcome.status());
        }
    }

    private IngestionOutcome record(InboundMessage message, long started, IngestionOutcome outcome) {
        health.processed(message.layer(), outcome.status(), (System.nanoTime() - started) / 1_000_000);
        return outcome;
    }
}
