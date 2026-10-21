package com.stellartrack.decision.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.common.exception.BusinessRuleException;
import com.stellartrack.common.exception.ConflictException;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.decision.domain.RecommendationStatus;
import com.stellartrack.decision.domain.RejectionReason;
import com.stellartrack.decision.persistence.ApprovalDecisionEntity;
import com.stellartrack.decision.persistence.ApprovalDecisionRepository;
import com.stellartrack.decision.persistence.DecisionSnapshotRepository;
import com.stellartrack.decision.persistence.RecommendationEntity;
import com.stellartrack.decision.persistence.RecommendationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual Approval Queue. Approve and Reject are decisions on a proposal, stored immutably with the proposal
 * snapshot. A rejected proposal is never sent to Betfair; an approved one is still fully revalidated before
 * any order is submitted.
 */
@Service
public class ManualApprovalService {

    private final RecommendationRepository recommendations;
    private final ApprovalDecisionRepository approvals;
    private final DecisionSnapshotRepository snapshots;
    private final ApplicationEventPublisher events;
    private final AuditTrail auditTrail;
    private final JsonCodec json;
    private final Clock clock;

    public ManualApprovalService(RecommendationRepository recommendations, ApprovalDecisionRepository approvals,
            DecisionSnapshotRepository snapshots, ApplicationEventPublisher events, AuditTrail auditTrail,
            JsonCodec json, Clock clock) {
        this.recommendations = recommendations;
        this.approvals = approvals;
        this.snapshots = snapshots;
        this.events = events;
        this.auditTrail = auditTrail;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RecommendationEntity> queue() {
        return recommendations.findByStatusOrderByCreatedAtAsc(RecommendationStatus.PENDING_APPROVAL);
    }

    @Transactional
    public RecommendationEntity approve(UUID recommendationId, UUID adminId, String actor) {
        RecommendationEntity recommendation = pending(recommendationId);
        Instant now = clock.instant();
        recommendation.decide(RecommendationStatus.APPROVED, now);
        approvals.save(new ApprovalDecisionEntity(recommendationId, ApprovalDecisionEntity.Decision.APPROVED, adminId,
                now, null, null, proposalSnapshot(recommendation)));
        auditTrail.record(AuditRecord.admin(actor, AuditAction.RECOMMENDATION_APPROVED)
                .entity("recommendation", recommendationId)
                .configuration(recommendation.getConfigurationId(), recommendation.getConfigurationVersion()));
        events.publishEvent(new RecommendationApprovedEvent(recommendationId, actor));
        return recommendation;
    }

    @Transactional
    public RecommendationEntity reject(UUID recommendationId, RejectionReason reason, String note, UUID adminId,
            String actor) {
        if (reason == null) {
            throw new BusinessRuleException("REJECTION_REASON_REQUIRED", "A rejection reason is required");
        }
        String trimmedNote = note == null || note.isBlank() ? null : note.trim();
        if (reason.noteRequired() && trimmedNote == null) {
            throw new BusinessRuleException("REJECTION_NOTE_REQUIRED", "A note is required for reason " + reason);
        }
        if (trimmedNote != null && trimmedNote.length() > 1000) {
            throw new BusinessRuleException("REJECTION_NOTE_TOO_LONG", "Note must be at most 1000 characters");
        }
        RecommendationEntity recommendation = pending(recommendationId);
        Instant now = clock.instant();
        recommendation.decide(RecommendationStatus.REJECTED, now);
        approvals.save(new ApprovalDecisionEntity(recommendationId, ApprovalDecisionEntity.Decision.REJECTED, adminId,
                now, reason, trimmedNote, proposalSnapshot(recommendation)));
        auditTrail.record(AuditRecord.admin(actor, AuditAction.RECOMMENDATION_REJECTED)
                .entity("recommendation", recommendationId)
                .configuration(recommendation.getConfigurationId(), recommendation.getConfigurationVersion())
                .details(Map.of("reasonCode", reason.name())));
        return recommendation;
    }

    /** Proposals not reviewed within their timeout expire; they are never executed afterwards. */
    @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
    @Transactional
    public void expireOverdue() {
        Instant now = clock.instant();
        for (RecommendationEntity r : recommendations.findByStatusAndExpiresAtBefore(RecommendationStatus.PENDING_APPROVAL, now)) {
            r.decide(RecommendationStatus.EXPIRED, now);
            auditTrail.record(AuditRecord.system(AuditAction.RECOMMENDATION_EXPIRED).entity("recommendation", r.getId()));
        }
    }

    /**
     * Expires every proposal still awaiting review, e.g. when Watch Mode starts, so none of them can become a real
     * bet. Expired proposals are never executed afterwards.
     *
     * @return the number of proposals expired
     */
    @Transactional
    public int expireAllPending(String reason, String actor) {
        return expire(recommendations.findByStatusOrderByCreatedAtAsc(RecommendationStatus.PENDING_APPROVAL), reason, actor);
    }

    /**
     * Withdraws the proposals of one configuration still awaiting review, e.g. when its live run is stopped or
     * switched, so none of them can become a real bet afterwards.
     *
     * @return the number of proposals withdrawn
     */
    @Transactional
    public int withdrawPending(UUID configurationId, String reason, String actor) {
        return expire(recommendations.findByStatusAndConfigurationIdOrderByCreatedAtAsc(RecommendationStatus.PENDING_APPROVAL,
                configurationId), reason, actor);
    }

    private int expire(List<RecommendationEntity> pending, String reason, String actor) {
        Instant now = clock.instant();
        for (RecommendationEntity r : pending) {
            r.decide(RecommendationStatus.EXPIRED, now);
            auditTrail.record(AuditRecord.admin(actor, AuditAction.RECOMMENDATION_EXPIRED).entity("recommendation", r.getId())
                    .details(Map.of("reason", reason)));
        }
        return pending.size();
    }

    private RecommendationEntity pending(UUID id) {
        RecommendationEntity recommendation = recommendations.findById(id)
                .orElseThrow(() -> new NotFoundException("Recommendation", id));
        if (recommendation.getStatus() != RecommendationStatus.PENDING_APPROVAL) {
            throw new ConflictException("RECOMMENDATION_ALREADY_DECIDED",
                    "This recommendation is " + recommendation.getStatus() + ", not awaiting review");
        }
        if (recommendation.getExpiresAt() != null && recommendation.getExpiresAt().isBefore(clock.instant())) {
            throw new ConflictException("RECOMMENDATION_EXPIRED",
                    "The review window for this recommendation has passed");
        }
        return recommendation;
    }

    /** The exact proposal the administrator saw, frozen with the decision. */
    private String proposalSnapshot(RecommendationEntity r) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("recommendationId", r.getId());
        proposal.put("marketId", r.getMarketId());
        proposal.put("selectionId", r.getSelectionId());
        proposal.put("side", r.getSide());
        proposal.put("marketType", r.getMarketType());
        proposal.put("proposedPrice", r.getProposedPrice());
        proposal.put("proposedPriceAt", r.getProposedPriceAt());
        proposal.put("proposedStake", r.getProposedStake());
        proposal.put("proposedLiability", r.getProposedLiability());
        proposal.put("strategyCode", r.getStrategyCode());
        proposal.put("strategyVersion", r.getStrategyVersion());
        proposal.put("configurationId", r.getConfigurationId());
        proposal.put("configurationVersion", r.getConfigurationVersion());
        snapshots.findByRecommendationId(r.getId()).ifPresent(s -> proposal.put("decisionSnapshotId", s.getId()));
        return json.write(proposal);
    }
}
