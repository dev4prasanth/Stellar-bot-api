package com.stellartrack.decision.persistence;

import com.stellartrack.decision.domain.RejectionReason;
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

@Entity
@Immutable
@Table(name = "approval_decision")
public class ApprovalDecisionEntity {

    public enum Decision {
        APPROVED,
        REJECTED
    }

    @Id
    private UUID id;

    @Column(name = "recommendation_id", nullable = false)
    private UUID recommendationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code")
    private RejectionReason reasonCode;

    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal_snapshot", nullable = false)
    private String proposalSnapshot;

    protected ApprovalDecisionEntity() {
    }

    public ApprovalDecisionEntity(UUID recommendationId, Decision decision, UUID adminId, Instant decidedAt,
            RejectionReason reasonCode, String note, String proposalSnapshot) {
        this.id = UUID.randomUUID();
        this.recommendationId = recommendationId;
        this.decision = decision;
        this.adminId = adminId;
        this.decidedAt = decidedAt;
        this.reasonCode = reasonCode;
        this.note = note;
        this.proposalSnapshot = proposalSnapshot;
    }

    public UUID getId() { return id; }
    public UUID getRecommendationId() { return recommendationId; }
    public Decision getDecision() { return decision; }
    public UUID getAdminId() { return adminId; }
    public Instant getDecidedAt() { return decidedAt; }
    public RejectionReason getReasonCode() { return reasonCode; }
    public String getNote() { return note; }
    public String getProposalSnapshot() { return proposalSnapshot; }
}
