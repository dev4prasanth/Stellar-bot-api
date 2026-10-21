package com.stellartrack.market.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "market_runner")
public class MarketRunnerEntity {

    @Embeddable
    public record Key(@Column(name = "market_id") String marketId, @Column(name = "selection_id") long selectionId)
            implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Column(name = "runner_name", nullable = false)
    private String runnerName;

    @Column(name = "sort_priority")
    private Integer sortPriority;

    @Column(nullable = false)
    private BigDecimal handicap;

    private String status;

    @Column(name = "adjustment_factor")
    private BigDecimal adjustmentFactor;

    @Column(name = "removal_date")
    private Instant removalDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String metadata;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MarketRunnerEntity() {
    }

    public MarketRunnerEntity(Key id) {
        this.id = id;
    }

    public void updateCatalogue(String runnerName, Integer sortPriority, BigDecimal handicap, String metadataJson,
            Instant now) {
        this.runnerName = runnerName;
        this.sortPriority = sortPriority;
        this.handicap = handicap;
        this.metadata = metadataJson;
        this.updatedAt = now;
    }

    public void updateStatus(String status, BigDecimal adjustmentFactor, Instant removalDate, Instant now) {
        this.status = status;
        this.adjustmentFactor = adjustmentFactor;
        this.removalDate = removalDate;
        this.updatedAt = now;
    }

    public Key getId() { return id; }
    public String getRunnerName() { return runnerName; }
    public Integer getSortPriority() { return sortPriority; }
    public BigDecimal getHandicap() { return handicap; }
    public String getStatus() { return status; }
    public BigDecimal getAdjustmentFactor() { return adjustmentFactor; }
    public Instant getRemovalDate() { return removalDate; }
    public String getMetadata() { return metadata; }
}
