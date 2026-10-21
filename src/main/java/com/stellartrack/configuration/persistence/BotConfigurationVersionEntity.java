package com.stellartrack.configuration.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable settings snapshot; decisions and orders reference (configuration id, version). */
@Entity
@Immutable
@Table(name = "bot_configuration_version")
public class BotConfigurationVersionEntity {

    @Id
    private UUID id;

    @Column(name = "configuration_id", nullable = false)
    private UUID configurationId;

    @Column(nullable = false)
    private int version;

    @Column(name = "settings_schema_version", nullable = false)
    private int settingsSchemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String settings;

    @Column(name = "change_note")
    private String changeNote;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BotConfigurationVersionEntity() {
    }

    public BotConfigurationVersionEntity(UUID configurationId, int version, int settingsSchemaVersion, String settings,
            String changeNote, String createdBy, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.configurationId = configurationId;
        this.version = version;
        this.settingsSchemaVersion = settingsSchemaVersion;
        this.settings = settings;
        this.changeNote = changeNote;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getConfigurationId() { return configurationId; }
    public int getVersion() { return version; }
    public int getSettingsSchemaVersion() { return settingsSchemaVersion; }
    public String getSettings() { return settings; }
    public String getChangeNote() { return changeNote; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
