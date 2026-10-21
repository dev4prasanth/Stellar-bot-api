package com.stellartrack.audit.application;

import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.audit.persistence.AuditEventEntity;
import com.stellartrack.audit.persistence.AuditEventRepository;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.common.util.SecretRedactor;
import com.stellartrack.common.web.CorrelationIds;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaAuditTrail implements AuditTrail {

    private final AuditEventRepository repository;
    private final JsonCodec json;
    private final Clock clock;

    public JpaAuditTrail(AuditEventRepository repository, JsonCodec json, Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(AuditRecord record) {
        repository.save(toEntity(record));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(AuditRecord record) {
        repository.save(toEntity(record));
    }

    private AuditEventEntity toEntity(AuditRecord record) {
        return new AuditEventEntity(
                clock.instant(),
                record.actorType(),
                record.actorId(),
                record.action(),
                record.entityType(),
                record.entityId(),
                record.outcome(),
                CorrelationIds.current(),
                record.configurationId(),
                record.configurationVersion(),
                json.write(SecretRedactor.redact(record.details())));
    }
}
