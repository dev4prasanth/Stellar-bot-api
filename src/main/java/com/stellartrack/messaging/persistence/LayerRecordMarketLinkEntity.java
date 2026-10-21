package com.stellartrack.messaging.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Contract-approved correlation of a layer record to a Betfair market/runner. */
@Entity
@Immutable
@Table(name = "layer_record_market_link")
public class LayerRecordMarketLinkEntity {

    @Embeddable
    public record Key(
            @Column(name = "record_id") UUID recordId,
            @Column(name = "market_id") String marketId,
            @Column(name = "selection_id") long selectionId) implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Column(name = "mapping_rule", nullable = false)
    private String mappingRule;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LayerRecordMarketLinkEntity() {
    }

    public LayerRecordMarketLinkEntity(Key id, String mappingRule, Instant createdAt) {
        this.id = id;
        this.mappingRule = mappingRule;
        this.createdAt = createdAt;
    }

    public Key getId() { return id; }
    public String getMappingRule() { return mappingRule; }
}
