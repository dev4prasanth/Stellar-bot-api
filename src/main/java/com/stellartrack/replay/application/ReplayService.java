package com.stellartrack.replay.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.common.capability.Capability;
import com.stellartrack.common.capability.CapabilityProvider;
import com.stellartrack.common.capability.CapabilityReport;
import com.stellartrack.common.capability.CapabilityState;
import com.stellartrack.common.exception.BusinessRuleException;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.common.util.Hashing;
import com.stellartrack.replay.config.ReplayProperties;
import com.stellartrack.replay.domain.ReplayEvaluationEngine;
import com.stellartrack.replay.domain.ReplayJobStatus;
import com.stellartrack.replay.persistence.ReplayJobEntity;
import com.stellartrack.replay.persistence.ReplayJobRepository;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Historical Replay jobs: genuine upload, storage, hashing, background validation and persisted status.
 * Evaluation runs only through an approved {@link ReplayEvaluationEngine}; none is registered, so well-formed
 * uploads finish as BLOCKED with the reason. Replay never references order placement.
 */
@Service
public class ReplayService implements CapabilityProvider {

    static final int MAX_ERRORS = 50;

    private final ReplayJobRepository jobs;
    private final ReplayFileStorage storage;
    private final ReplayProperties properties;
    private final Optional<ReplayEvaluationEngine> engine;
    private final AuditTrail auditTrail;
    private final JsonCodec json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ReplayService(ReplayJobRepository jobs, ReplayFileStorage storage, ReplayProperties properties,
            ObjectProvider<ReplayEvaluationEngine> engine, AuditTrail auditTrail, JsonCodec json, Clock clock,
            PlatformTransactionManager txManager) {
        this.jobs = jobs;
        this.storage = storage;
        this.properties = properties;
        this.engine = Optional.ofNullable(engine.getIfAvailable());
        this.auditTrail = auditTrail;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public ReplayJobEntity upload(String filename, String contentType, byte[] content, UUID configurationId,
            Integer configurationVersion, String actor) throws IOException {
        String extension = extension(filename);
        if (!properties.allowedExtensions().contains(extension)) {
            throw new BusinessRuleException("REPLAY_FILE_TYPE", "Allowed file types: " + properties.allowedExtensions());
        }
        if (content.length == 0) {
            throw new BusinessRuleException("REPLAY_FILE_EMPTY", "The file is empty");
        }
        String key = storage.store(new ByteArrayInputStream(content), extension);
        String safeName = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        ReplayJobEntity job = tx.execute(s -> {
            ReplayJobEntity saved = jobs.save(ReplayJobEntity.uploaded(safeName.length() > 255 ? safeName.substring(0, 255) : safeName,
                    contentType, content.length, Hashing.sha256Hex(content), key, configurationId, configurationVersion,
                    actor, clock.instant()));
            auditTrail.record(AuditRecord.admin(actor, AuditAction.REPLAY_UPLOADED).entity("replay_job", saved.getId())
                    .details(Map.of("sha256", saved.getSha256(), "sizeBytes", saved.getSizeBytes())));
            return saved;
        });
        return job;
    }

    /** Background validation; invoked after upload commits. */
    @Async
    public void process(UUID jobId) {
        ReplayJobEntity job = tx.execute(s -> {
            ReplayJobEntity j = jobs.findById(jobId).orElseThrow();
            j.validating(clock.instant());
            return j;
        });
        Path file = storage.resolve(job.getStorageKey());
        List<String> errors = new ArrayList<>();
        int rows = wellFormedRows(file, extension(job.getStorageKey()), errors);
        ReplayJobStatus status;
        String reason;
        if (!errors.isEmpty()) {
            status = ReplayJobStatus.VALIDATION_FAILED;
            reason = "File is not well-formed " + extension(job.getStorageKey()).toUpperCase(Locale.ROOT);
        } else if (engine.isEmpty()) {
            status = ReplayJobStatus.BLOCKED;
            reason = "Stored and well-formed. Evaluation is blocked: the approved historical input schema and"
                    + " evaluation engine have not been supplied.";
        } else {
            errors.addAll(engine.get().validate(file));
            status = errors.isEmpty() ? ReplayJobStatus.BLOCKED : ReplayJobStatus.VALIDATION_FAILED;
            reason = errors.isEmpty() ? "Validated; evaluation run is started separately" : "Schema validation failed";
        }
        List<String> limited = errors.size() > MAX_ERRORS ? errors.subList(0, MAX_ERRORS) : errors;
        tx.executeWithoutResult(s -> {
            jobs.findById(jobId).orElseThrow().finish(status, reason, rows, json.write(limited), clock.instant());
            auditTrail.record(AuditRecord.system(AuditAction.REPLAY_STATUS_CHANGED).entity("replay_job", jobId)
                    .details(Map.of("status", status.name())));
        });
    }

    public Page<ReplayJobEntity> list(Pageable pageable) {
        return jobs.findAllByOrderByCreatedAtDesc(pageable);
    }

    public ReplayJobEntity get(UUID id) {
        return jobs.findById(id).orElseThrow(() -> new NotFoundException("Replay job", id));
    }

    /** Structural check only (CSV with consistent columns, or parseable JSON); never interprets the content. */
    int wellFormedRows(Path file, String extension, List<String> errors) {
        try {
            if ("json".equals(extension)) {
                JsonNode root = json.readTree(Files.readAllBytes(file));
                return root.isArray() ? root.size() : 1;
            }
            int expected = -1;
            int rows = 0;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                int lineNo = 0;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    if (line.isBlank()) {
                        continue;
                    }
                    int columns = line.split(",", -1).length;
                    if (expected < 0) {
                        expected = columns;
                    } else {
                        rows++;
                        if (columns != expected && errors.size() < MAX_ERRORS) {
                            errors.add("Line " + lineNo + ": expected " + expected + " columns, found " + columns);
                        }
                    }
                }
            }
            if (expected < 0) {
                errors.add("No header row");
            }
            return rows;
        } catch (JacksonException e) {
            errors.add("Invalid JSON");
            return 0;
        } catch (IOException e) {
            errors.add("File could not be read");
            return 0;
        }
    }

    private static String extension(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    @Override
    public List<CapabilityReport> capabilities() {
        return List.of(engine.isPresent()
                ? CapabilityReport.of(Capability.REPLAY_EVALUATION, CapabilityState.IMPLEMENTED,
                        "Engine " + engine.get().version() + " registered", List.of())
                : CapabilityReport.blocked(Capability.REPLAY_EVALUATION,
                        "Upload, storage and job tracking work; evaluation needs an approved schema and engine",
                        "Historical input file schema", "Approved evaluation engine and version"));
    }
}
