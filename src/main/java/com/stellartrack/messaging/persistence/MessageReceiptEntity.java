package com.stellartrack.messaging.persistence;

import com.stellartrack.messaging.domain.ErrorClass;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.messaging.domain.ReceiptStatus;
import com.stellartrack.common.util.Hashing;
import com.stellartrack.common.util.SecretRedactor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "message_receipt")
public class MessageReceiptEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Layer layer;

    @Column(name = "queue_name")
    private String queueName;

    @Column(name = "exchange_name")
    private String exchangeName;

    @Column(name = "routing_key")
    private String routingKey;

    @Column(name = "broker_message_id")
    private String brokerMessageId;

    @Column(nullable = false)
    private boolean redelivered;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "payload_bytes", nullable = false)
    private int payloadBytes;

    @Column(name = "payload_sha256", nullable = false)
    private String payloadSha256;

    @Column(name = "payload_raw")
    private byte[] payloadRaw;

    @Column(name = "contract_version")
    private String contractVersion;

    @Column(name = "source_message_id")
    private String sourceMessageId;

    @Column(name = "dedup_key")
    private String dedupKey;

    @Column(name = "source_event_at")
    private Instant sourceEventAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "processing_ms")
    private Integer processingMs;

    @Column(nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReceiptStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_class")
    private ErrorClass errorClass;

    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "reprocessed_from")
    private UUID reprocessedFrom;

    protected MessageReceiptEntity() {
    }

    public static MessageReceiptEntity received(InboundMessage message, boolean storeRaw, String correlationId) {
        MessageReceiptEntity e = new MessageReceiptEntity();
        e.id = UUID.randomUUID();
        e.layer = message.layer();
        e.queueName = message.queue();
        e.exchangeName = message.exchange();
        e.routingKey = message.routingKey();
        e.brokerMessageId = SecretRedactor.truncate(message.brokerMessageId(), 255);
        e.redelivered = message.redelivered();
        e.contentType = SecretRedactor.truncate(message.contentType(), 100);
        e.payloadBytes = message.body().length;
        e.payloadSha256 = Hashing.sha256Hex(message.body());
        e.payloadRaw = storeRaw ? message.body() : null;
        e.receivedAt = message.receivedAt();
        e.attempt = Math.max(1, message.attempt());
        e.status = ReceiptStatus.RECEIVED;
        e.correlationId = correlationId;
        e.reprocessedFrom = message.reprocessedFrom();
        return e;
    }

    public void identify(String contractVersion, String sourceMessageId, String dedupKey, Instant sourceEventAt) {
        this.contractVersion = contractVersion;
        this.sourceMessageId = SecretRedactor.truncate(sourceMessageId, 255);
        this.dedupKey = dedupKey;
        this.sourceEventAt = sourceEventAt;
    }

    public void complete(ReceiptStatus status, ErrorClass errorClass, String detail, Instant processedAt) {
        this.status = status;
        this.errorClass = errorClass;
        this.errorDetail = SecretRedactor.truncate(detail, 1000);
        this.processedAt = processedAt;
        this.processingMs = (int) Math.max(0, processedAt.toEpochMilli() - receivedAt.toEpochMilli());
    }

    public void markReprocessed(Instant now) {
        this.status = ReceiptStatus.REPROCESSED;
        this.processedAt = now;
    }

    public UUID getId() { return id; }
    public Layer getLayer() { return layer; }
    public String getQueueName() { return queueName; }
    public String getExchangeName() { return exchangeName; }
    public String getRoutingKey() { return routingKey; }
    public String getBrokerMessageId() { return brokerMessageId; }
    public boolean isRedelivered() { return redelivered; }
    public String getContentType() { return contentType; }
    public int getPayloadBytes() { return payloadBytes; }
    public String getPayloadSha256() { return payloadSha256; }
    public byte[] getPayloadRaw() { return payloadRaw; }
    public String getContractVersion() { return contractVersion; }
    public String getSourceMessageId() { return sourceMessageId; }
    public String getDedupKey() { return dedupKey; }
    public Instant getSourceEventAt() { return sourceEventAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public Integer getProcessingMs() { return processingMs; }
    public int getAttempt() { return attempt; }
    public ReceiptStatus getStatus() { return status; }
    public ErrorClass getErrorClass() { return errorClass; }
    public String getErrorDetail() { return errorDetail; }
    public String getCorrelationId() { return correlationId; }
    public UUID getReprocessedFrom() { return reprocessedFrom; }
}
