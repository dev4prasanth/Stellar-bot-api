package com.stellartrack.messaging.persistence;

import com.stellartrack.messaging.domain.Layer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A validated layer record. Rows are never updated: a revised source message is a new dedup identity. */
@Entity
@Immutable
@Table(name = "layer_source_record")
public class LayerSourceRecordEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Layer layer;

    @Column(name = "receipt_id", nullable = false)
    private UUID receiptId;

    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

    @Column(name = "contract_version", nullable = false)
    private String contractVersion;

    @Column(name = "source_event_at")
    private Instant sourceEventAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "normalized_payload", nullable = false)
    private String normalizedPayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LayerSourceRecordEntity() {
    }

    public LayerSourceRecordEntity(UUID id, Layer layer, UUID receiptId, String dedupKey, String contractVersion,
            Instant sourceEventAt, String normalizedPayload, Instant createdAt) {
        this.id = id;
        this.layer = layer;
        this.receiptId = receiptId;
        this.dedupKey = dedupKey;
        this.contractVersion = contractVersion;
        this.sourceEventAt = sourceEventAt;
        this.normalizedPayload = normalizedPayload;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public Layer getLayer() { return layer; }
    public UUID getReceiptId() { return receiptId; }
    public String getDedupKey() { return dedupKey; }
    public String getContractVersion() { return contractVersion; }
    public Instant getSourceEventAt() { return sourceEventAt; }
    public String getNormalizedPayload() { return normalizedPayload; }
    public Instant getCreatedAt() { return createdAt; }
}
