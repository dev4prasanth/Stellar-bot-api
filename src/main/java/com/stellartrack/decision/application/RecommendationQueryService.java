package com.stellartrack.decision.application;

import com.stellartrack.common.api.PageResponse;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.decision.persistence.ApprovalDecisionRepository;
import com.stellartrack.decision.persistence.DecisionOutcomeEvaluationEntity;
import com.stellartrack.decision.persistence.DecisionOutcomeEvaluationRepository;
import com.stellartrack.decision.persistence.DecisionSnapshotEntity;
import com.stellartrack.decision.persistence.DecisionSnapshotRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Read model for recommendations, the approval queue and decision drill-downs. */
@Service
@Transactional(readOnly = true)
public class RecommendationQueryService {

    public record RecommendationFilter(Instant from, Instant to, String status, String source, String mode,
            UUID configurationId, String marketId, String outcome) {
    }

    public record RecommendationRow(UUID id, Instant createdAt, Instant decidedAt, Instant expiresAt, String mode,
            String selectionSource, UUID configurationId, String configurationName, Integer configurationVersion,
            String marketId, String venue, Instant marketStart, String marketType, long selectionId, String runnerName,
            String side, String status, String strategyCode, String strategyVersion, BigDecimal proposedPrice,
            Instant proposedPriceAt, BigDecimal proposedStake, BigDecimal proposedLiability, String noBetReason,
            String approvalDecision, String rejectionReason, String outcomeClassification, boolean submitted,
            boolean matched) {
    }

    public record SnapshotView(UUID id, Instant capturedAt, String strategyCode, String strategyVersion,
            String formulaReference, JsonNode layerRefs, JsonNode inputs, JsonNode intermediate, JsonNode gates,
            JsonNode flags, BigDecimal finalScore, String result, JsonNode reasons, JsonNode staking,
            JsonNode marketState) {
    }

    public record RecommendationDetail(RecommendationRow recommendation, SnapshotView snapshot,
            Map<String, Object> approval, List<Map<String, Object>> outcomeEvaluations, List<UUID> orderIntentIds) {
    }

    private static final String SELECT = """
            select d.*, c.name as configuration_name, m.venue, m.market_start_time, r.runner_name, rec.expires_at,
                   rec.proposed_price_at
            from v_decision d
            join recommendation rec on rec.id = d.recommendation_id
            join market m on m.market_id = d.market_id
            left join market_runner r on r.market_id = d.market_id and r.selection_id = d.selection_id
            left join bot_configuration c on c.id = d.configuration_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final DecisionSnapshotRepository snapshots;
    private final ApprovalDecisionRepository approvals;
    private final DecisionOutcomeEvaluationRepository evaluations;
    private final JsonCodec json;

    public RecommendationQueryService(NamedParameterJdbcTemplate jdbc, DecisionSnapshotRepository snapshots,
            ApprovalDecisionRepository approvals, DecisionOutcomeEvaluationRepository evaluations, JsonCodec json) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.approvals = approvals;
        this.evaluations = evaluations;
        this.json = json;
    }

    public PageResponse<RecommendationRow> search(RecommendationFilter f, int page, int size) {
        StringBuilder where = new StringBuilder(" where d.created_at >= :from and d.created_at < :to");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", Timestamp.from(f.from()))
                .addValue("to", Timestamp.from(f.to()));
        append(where, p, "d.status", "status", f.status());
        append(where, p, "d.selection_source", "source", f.source());
        append(where, p, "d.mode", "mode", f.mode());
        append(where, p, "d.configuration_id", "cid", f.configurationId());
        append(where, p, "d.market_id", "marketId", f.marketId());
        append(where, p, "d.outcome_classification", "outcome", f.outcome());
        Long total = jdbc.queryForObject("select count(*) from (" + SELECT + where + ") t", p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<RecommendationRow> rows = jdbc.query(SELECT + where + " order by d.created_at desc limit :limit offset :offset",
                p, (rs, i) -> row(rs));
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    public List<RecommendationRow> approvalQueue() {
        return jdbc.query(SELECT + " where d.status = 'PENDING_APPROVAL' order by d.created_at", new MapSqlParameterSource(),
                (rs, i) -> row(rs));
    }

    public RecommendationDetail detail(UUID id) {
        List<RecommendationRow> rows = jdbc.query(SELECT + " where d.recommendation_id = :id",
                new MapSqlParameterSource("id", id), (rs, i) -> row(rs));
        if (rows.isEmpty()) {
            throw new NotFoundException("Recommendation", id);
        }
        SnapshotView snapshot = snapshots.findByRecommendationId(id).map(this::view).orElse(null);
        Map<String, Object> approval = approvals.findByRecommendationId(id).map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decision", a.getDecision());
            m.put("decidedAt", a.getDecidedAt());
            m.put("adminId", a.getAdminId());
            m.put("reasonCode", a.getReasonCode());
            m.put("note", a.getNote());
            m.put("proposalSnapshot", json.readTree(a.getProposalSnapshot()));
            return m;
        }).orElse(null);
        List<Map<String, Object>> outcomes = evaluations.findByRecommendationIdOrderByEvaluatedAtDesc(id).stream()
                .map(this::outcome).toList();
        List<UUID> intents = jdbc.queryForList("select id from order_intent where recommendation_id = :id order by created_at",
                new MapSqlParameterSource("id", id), UUID.class);
        return new RecommendationDetail(rows.getFirst(), snapshot, approval, outcomes, intents);
    }

    private Map<String, Object> outcome(DecisionOutcomeEvaluationEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleVersion", e.getRuleVersion());
        m.put("evaluatedAt", e.getEvaluatedAt());
        m.put("classification", e.getClassification());
        m.put("runnerResult", e.getRunnerResult());
        m.put("deadHeat", e.isDeadHeat());
        m.put("evidence", json.readTree(e.getEvidence()));
        m.put("hypotheticalProfit", e.getHypotheticalProfit());
        return m;
    }

    public SnapshotView view(DecisionSnapshotEntity s) {
        return new SnapshotView(s.getId(), s.getCapturedAt(), s.getStrategyCode(), s.getStrategyVersion(),
                s.getFormulaReference(), json.readTree(s.getLayerRefs()), json.readTree(s.getInputs()),
                json.readTree(s.getIntermediate()), json.readTree(s.getGates()), json.readTree(s.getFlags()),
                s.getFinalScore(), s.getResult(), json.readTree(s.getReasons()),
                s.getStaking() == null ? null : json.readTree(s.getStaking()), json.readTree(s.getMarketState()));
    }

    private static void append(StringBuilder where, MapSqlParameterSource p, String column, String name, Object value) {
        if (value != null) {
            where.append(" and ").append(column).append(" = :").append(name);
            p.addValue(name, value);
        }
    }

    private static RecommendationRow row(ResultSet rs) throws SQLException {
        return new RecommendationRow((UUID) rs.getObject("recommendation_id"), ts(rs, "created_at"), ts(rs, "decided_at"),
                ts(rs, "expires_at"), rs.getString("mode"), rs.getString("selection_source"),
                (UUID) rs.getObject("configuration_id"), rs.getString("configuration_name"),
                (Integer) rs.getObject("configuration_version"), rs.getString("market_id"), rs.getString("venue"),
                ts(rs, "market_start_time"), rs.getString("market_type"), rs.getLong("selection_id"),
                rs.getString("runner_name"), rs.getString("side"), rs.getString("status"), rs.getString("strategy_code"),
                rs.getString("strategy_version"), rs.getBigDecimal("proposed_price"), ts(rs, "proposed_price_at"),
                rs.getBigDecimal("proposed_stake"), rs.getBigDecimal("proposed_liability"), rs.getString("no_bet_reason"),
                rs.getString("approval_decision"), rs.getString("rejection_reason"),
                rs.getString("outcome_classification"), rs.getBoolean("submitted"), rs.getBoolean("matched"));
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
