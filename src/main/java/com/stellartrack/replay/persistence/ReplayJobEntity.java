package com.stellartrack.replay.persistence;

import com.stellartrack.replay.domain.ReplayJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "replay_job")
public class ReplayJobEntity {

    @Id
    private UUID id;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReplayJobStatus status;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "configuration_id")
    private UUID configurationId;

    @Column(name = "configuration_version")
    private Integer configurationVersion;

    @Column(name = "engine_version")
    private String engineVersion;

    @Column(name = "total_rows")
    private Integer totalRows;

    @Column(name = "processed_rows", nullable = false)
    private int processedRows;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String errors;

    @JdbcTypeCode(SqlTypes.JSON)
    private String result;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ReplayJobEntity() {
    }

    public static ReplayJobEntity uploaded(String filename, String contentType, long size, String sha256,
            String storageKey, UUID configurationId, Integer configurationVersion, String actor, Instant now) {
        ReplayJobEntity job = new ReplayJobEntity();
        job.id = UUID.randomUUID();
        job.originalFilename = filename;
        job.contentType = contentType;
        job.sizeBytes = size;
        job.sha256 = sha256;
        job.storageKey = storageKey;
        job.status = ReplayJobStatus.UPLOADED;
        job.configurationId = configurationId;
        job.configurationVersion = configurationVersion;
        job.errors = "[]";
        job.createdBy = actor;
        job.createdAt = now;
        return job;
    }

    public void validating(Instant now) {
        this.status = ReplayJobStatus.VALIDATING;
        this.startedAt = now;
    }

    public void finish(ReplayJobStatus status, String reason, Integer totalRows, String errorsJson, Instant now) {
        this.status = status;
        this.statusReason = reason;
        this.totalRows = totalRows;
        this.errors = errorsJson;
        this.finishedAt = now;
    }

    public UUID getId() { return id; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getStorageKey() { return storageKey; }
    public ReplayJobStatus getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public UUID getConfigurationId() { return configurationId; }
    public Integer getConfigurationVersion() { return configurationVersion; }
    public String getEngineVersion() { return engineVersion; }
    public Integer getTotalRows() { return totalRows; }
    public int getProcessedRows() { return processedRows; }
    public String getErrors() { return errors; }
    public String getResult() { return result; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
