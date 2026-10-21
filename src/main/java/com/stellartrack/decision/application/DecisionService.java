package com.stellartrack.decision.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditOutcome;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.decision.domain.DecisionSnapshot;
import com.stellartrack.decision.domain.RecommendationStatus;
import com.stellartrack.decision.persistence.DecisionSnapshotEntity;
import com.stellartrack.decision.persistence.DecisionSnapshotRepository;
import com.stellartrack.decision.persistence.RecommendationEntity;
import com.stellartrack.decision.persistence.RecommendationRepository;
import com.stellartrack.staking.domain.StakeCalculation;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists evaluated recommendations together with their immutable decision snapshots. */
@Service
public class DecisionService {

    private final RecommendationRepository recommendations;
    private final DecisionSnapshotRepository snapshots;
    private final AuditTrail auditTrail;
    private final JsonCodec json;
    private final Clock clock;

    public DecisionService(RecommendationRepository recommendations, DecisionSnapshotRepository snapshots,
            AuditTrail auditTrail, JsonCodec json, Clock clock) {
        this.recommendations = recommendations;
        this.snapshots = snapshots;
        this.auditTrail = auditTrail;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public RecommendationEntity record(NewDecision decision) {
        Instant now = clock.instant();
        DecisionSnapshot snapshot = decision.snapshot();
        StakeCalculation stake = snapshot.staking();
        DecisionSnapshot.MarketState market = snapshot.marketState();
        RecommendationEntity entity = recommendations.save(RecommendationEntity.create(
                decision.configurationId(), decision.configurationVersion(), decision.mode(), decision.source(),
                decision.marketId(), decision.selectionId(), decision.side(), decision.marketType(), decision.status(),
                decision.requiresApproval(), snapshot.strategyCode(), snapshot.strategyVersion(),
                market == null ? null : market.proposedPrice(), market == null ? null : market.priceAsOf(),
                stake == null ? null : stake.stake(), stake == null ? null : stake.liability(),
                stake == null ? null : stake.method(), decision.noBetReason(), now, decision.expiresAt()));
        snapshots.save(new DecisionSnapshotEntity(entity.getId(), DecisionSnapshot.SCHEMA_VERSION, now,
                snapshot.strategyCode(), snapshot.strategyVersion(), snapshot.formulaReference(),
                json.write(snapshot.layerRefs()), json.write(snapshot.inputs()), json.write(snapshot.intermediate()),
                json.write(snapshot.gates()), json.write(snapshot.flags()), snapshot.finalScore(), snapshot.result(),
                json.write(snapshot.reasons()), stake == null ? null : json.write(stake), json.write(market)));
        if (decision.status() != RecommendationStatus.SKIPPED) {
            auditTrail.record(AuditRecord.system(AuditAction.RECOMMENDATION_CREATED)
                    .entity("recommendation", entity.getId())
                    .configuration(decision.configurationId(), decision.configurationVersion())
                    .outcome(AuditOutcome.INFO)
                    .details(Map.of("status", decision.status().name(), "marketId", decision.marketId(),
                            "selectionId", decision.selectionId(), "side", decision.side().name())));
        }
        return entity;
    }

    @Transactional(readOnly = true)
    public RecommendationEntity require(UUID id) {
        return recommendations.findById(id).orElseThrow(() -> new NotFoundException("Recommendation", id));
    }

    @Transactional
    public void noteNotExecuted(UUID id, String reason) {
        require(id).noteNotExecuted(reason);
    }
}
