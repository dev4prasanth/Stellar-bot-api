package com.stellartrack.messaging.application;

import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.messaging.domain.ErrorClass;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.NormalizedLayerRecord;
import com.stellartrack.messaging.domain.ReceiptStatus;
import com.stellartrack.messaging.persistence.LayerRecordMarketLinkEntity;
import com.stellartrack.messaging.persistence.LayerRecordMarketLinkRepository;
import com.stellartrack.messaging.persistence.LayerSourceRecordEntity;
import com.stellartrack.messaging.persistence.LayerSourceRecordRepository;
import com.stellartrack.messaging.persistence.MessageReceiptEntity;
import com.stellartrack.messaging.persistence.MessageReceiptRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction boundaries for ingestion. Each step commits independently so the receipt always reflects the
 * last durable outcome, and the broker delivery is acknowledged only after that commit.
 */
@Component
public class IngestionStore {

    /** Outcome of the idempotent persistence step. */
    public record PersistResult(ReceiptStatus status, UUID recordId) {
    }

    private final MessageReceiptRepository receipts;
    private final LayerSourceRecordRepository records;
    private final LayerRecordMarketLinkRepository links;
    private final JsonCodec json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public IngestionStore(MessageReceiptRepository receipts, LayerSourceRecordRepository records,
            LayerRecordMarketLinkRepository links, JsonCodec json, Clock clock, PlatformTransactionManager txManager) {
        this.receipts = receipts;
        this.records = records;
        this.links = links;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UUID openReceipt(InboundMessage message, boolean storeRaw, String correlationId) {
        return tx.execute(status -> receipts.save(MessageReceiptEntity.received(message, storeRaw, correlationId)).getId());
    }

    public void completeReceipt(UUID receiptId, ReceiptStatus status, ErrorClass errorClass, String detail) {
        tx.executeWithoutResult(s -> receipts.findById(receiptId)
                .ifPresent(receipt -> receipt.complete(status, errorClass, detail, clock.instant())));
    }

    public void identifyReceipt(UUID receiptId, NormalizedLayerRecord record) {
        tx.executeWithoutResult(s -> receipts.findById(receiptId).ifPresent(receipt -> receipt.identify(
                record.contractVersion(), record.sourceMessageId(), record.dedupKey(), record.sourceEventAt())));
    }

    /**
     * Stores the record and marks the receipt ACCEPTED atomically, or marks it DUPLICATE when the
     * contract-defined identity was already accepted (including a concurrent consumer winning the race).
     */
    public PersistResult persistAccepted(UUID receiptId, NormalizedLayerRecord record) {
        try {
            return tx.execute(status -> {
                MessageReceiptEntity receipt = receipts.findById(receiptId).orElseThrow();
                Optional<LayerSourceRecordEntity> existing = records.findByLayerAndDedupKey(receipt.getLayer(), record.dedupKey());
                Instant now = clock.instant();
                if (existing.isPresent()) {
                    receipt.complete(ReceiptStatus.DUPLICATE, null, "Already accepted as record " + existing.get().getId(), now);
                    return new PersistResult(ReceiptStatus.DUPLICATE, existing.get().getId());
                }
                LayerSourceRecordEntity entity = records.save(new LayerSourceRecordEntity(UUID.randomUUID(),
                        receipt.getLayer(), receiptId, record.dedupKey(), record.contractVersion(),
                        record.sourceEventAt(), json.write(record.payload()), now));
                record.marketLinks().forEach(link -> links.save(new LayerRecordMarketLinkEntity(
                        new LayerRecordMarketLinkEntity.Key(entity.getId(), link.marketId(), link.selectionId()),
                        link.mappingRule(), now)));
                receipt.complete(ReceiptStatus.ACCEPTED, null, null, now);
                records.flush();
                return new PersistResult(ReceiptStatus.ACCEPTED, entity.getId());
            });
        } catch (DataIntegrityViolationException race) {
            completeReceipt(receiptId, ReceiptStatus.DUPLICATE, null, "Concurrent duplicate delivery");
            return new PersistResult(ReceiptStatus.DUPLICATE, null);
        }
    }

    public Optional<MessageReceiptEntity> findReceipt(UUID receiptId) {
        return receipts.findById(receiptId);
    }

    public void markReprocessed(UUID receiptId) {
        tx.executeWithoutResult(s -> receipts.findById(receiptId).ifPresent(r -> r.markReprocessed(clock.instant())));
    }
}
