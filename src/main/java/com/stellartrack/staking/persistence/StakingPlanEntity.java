package com.stellartrack.staking.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "staking_plan")
public class StakingPlanEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "source_filename", nullable = false)
    private String sourceFilename;

    @Column(name = "source_sha256", nullable = false)
    private String sourceSha256;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String steps;

    @Column(name = "step_count", nullable = false)
    private int stepCount;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StakingPlanEntity() {
    }

    public StakingPlanEntity(UUID id, String name, String sourceFilename, String sourceSha256, int schemaVersion,
            String steps, int stepCount, String createdBy, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.sourceFilename = sourceFilename;
        this.sourceSha256 = sourceSha256;
        this.schemaVersion = schemaVersion;
        this.steps = steps;
        this.stepCount = stepCount;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSourceFilename() { return sourceFilename; }
    public String getSourceSha256() { return sourceSha256; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getSteps() { return steps; }
    public int getStepCount() { return stepCount; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
