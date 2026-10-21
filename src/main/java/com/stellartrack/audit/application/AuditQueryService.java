package com.stellartrack.audit.application;

import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditOutcome;
import com.stellartrack.audit.persistence.AuditEventEntity;
import com.stellartrack.audit.persistence.AuditEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuditQueryService {

    public record AuditFilter(AuditAction action, AuditOutcome outcome, String entityType, String entityId,
            String correlationId, Instant from, Instant to) {
    }

    private final AuditEventRepository repository;

    public AuditQueryService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public Page<AuditEventEntity> search(AuditFilter filter, Pageable pageable) {
        List<Specification<AuditEventEntity>> specs = new ArrayList<>();
        specs.add((root, query, cb) -> cb.not(root.get("action").in(AuditAction.RETIRED_AUTHENTICATION)));
        if (filter.action() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("action"), filter.action()));
        }
        if (filter.outcome() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("outcome"), filter.outcome()));
        }
        if (filter.entityType() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("entityType"), filter.entityType()));
        }
        if (filter.entityId() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("entityId"), filter.entityId()));
        }
        if (filter.correlationId() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("correlationId"), filter.correlationId()));
        }
        if (filter.from() != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), filter.from()));
        }
        if (filter.to() != null) {
            specs.add((root, query, cb) -> cb.lessThan(root.get("occurredAt"), filter.to()));
        }
        return repository.findAll(Specification.allOf(specs), pageable);
    }
}
