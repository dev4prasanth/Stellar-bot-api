package com.stellartrack.messaging.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.common.exception.BusinessRuleException;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.web.CorrelationIds;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.persistence.MessageReceiptEntity;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Re-runs a quarantined, invalid or contract-pending receipt through the pipeline from its stored raw payload
 * (for example after a contract is registered or a validator bug is fixed). Safe against duplicate bets:
 * the pipeline only stores source data idempotently; it never submits orders.
 */
@Service
public class QuarantineReprocessService {

    private final IngestionStore store;
    private final IngestionPipeline pipeline;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public QuarantineReprocessService(IngestionStore store, IngestionPipeline pipeline, AuditTrail auditTrail,
            Clock clock) {
        this.store = store;
        this.pipeline = pipeline;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    public IngestionOutcome reprocess(UUID receiptId, String actorId) {
        MessageReceiptEntity original = store.findReceipt(receiptId)
                .orElseThrow(() -> new NotFoundException("Message receipt", receiptId));
        if (!original.getStatus().isReprocessable()) {
            throw new BusinessRuleException("RECEIPT_NOT_REPROCESSABLE",
                    "Only quarantined, invalid or contract-pending receipts can be reprocessed");
        }
        if (original.getPayloadRaw() == null) {
            throw new BusinessRuleException("RAW_PAYLOAD_UNAVAILABLE",
                    "The raw payload was not retained; recover the message from the dead-letter queue instead");
        }
        InboundMessage message = new InboundMessage(original.getLayer(), original.getPayloadRaw(),
                original.getContentType(), original.getBrokerMessageId(), original.getQueueName(),
                original.getExchangeName(), original.getRoutingKey(), true, 1, Map.of(), clock.instant(),
                original.getId());
        IngestionOutcome outcome = pipeline.process(message, CorrelationIds.current());
        store.markReprocessed(original.getId());
        auditTrail.recordIndependently(AuditRecord.admin(actorId, AuditAction.MESSAGE_REPROCESS_REQUESTED)
                .entity("message_receipt", receiptId)
                .details(Map.of("newReceiptId", String.valueOf(outcome.receiptId()), "result", outcome.status().name())));
        return outcome;
    }
}
