package com.stellartrack.reporting.application;

import com.stellartrack.common.api.PageResponse;
import com.stellartrack.reporting.domain.ReportFilter;
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

/** Row-level sources behind the charts: settled units, manual rejections and stake/odds analysis. */
@Service
@Transactional(readOnly = true)
public class OutcomeReportService {

    public record SettledRow(String betId, String betStatus, int revision, boolean corrected, String outcomeClass,
            Instant settledAt, String mode, UUID configurationId, String configurationName, String marketId,
            String venue, Instant marketStart, String marketType, long selectionId, String runnerName, String side,
            BigDecimal requestedPrice, BigDecimal priceMatched, Boolean priceReduced, BigDecimal sizeSettled,
            BigDecimal profit, UUID recommendationId) {
    }

    public record RejectionRow(UUID recommendationId, Instant createdAt, Instant rejectedAt, String rejectionReason,
            UUID configurationId, String configurationName, String strategyCode, String marketId, String venue,
            Instant marketStart, String marketType, long selectionId, String runnerName, String side,
            BigDecimal proposedPrice, BigDecimal proposedStake, String outcomeClassification, String runnerResult,
            Boolean deadHeat) {
    }

    public record BreakdownRow(String side, String marketType, String mode, String configurationName, String oddsBand,
            long units, BigDecimal matchedStake, BigDecimal layLiability, BigDecimal profit) {
    }

    static final String SETTLED_SELECT = """
            select v.*, c.name as configuration_name, m.venue, m.market_start_time, r.runner_name
            from v_settled_bet v
            join market m on m.market_id = v.market_id
            left join market_runner r on r.market_id = v.market_id and r.selection_id = v.selection_id
            left join bot_configuration c on c.id = v.configuration_id
            """;

    static final String REJECTION_SELECT = """
            select d.*, c.name as configuration_name, m.venue, m.market_start_time, r.runner_name
            from v_decision d
            join market m on m.market_id = d.market_id
            left join market_runner r on r.market_id = d.market_id and r.selection_id = d.selection_id
            left join bot_configuration c on c.id = d.configuration_id
            """;

    /** Odds bands used across reports; boundaries are inclusive lower / exclusive upper. */
    static final String ODDS_BAND = """
            case when coalesce(v.price_matched, v.requested_price) < 2 then '1.01-1.99'
                 when coalesce(v.price_matched, v.requested_price) < 4 then '2.00-3.99'
                 when coalesce(v.price_matched, v.requested_price) < 8 then '4.00-7.99'
                 when coalesce(v.price_matched, v.requested_price) < 16 then '8.00-15.99'
                 else '16.00+' end""";

    private final NamedParameterJdbcTemplate jdbc;

    public OutcomeReportService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<SettledRow> settled(ReportFilter filter, String outcomeClass, int page, int size) {
        MapSqlParameterSource p = ReportSql.params();
        String where = ReportSql.where("v", "v.settled_at", filter, p) + classFilter(outcomeClass, p);
        Long total = jdbc.queryForObject("select count(*) from v_settled_bet v" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<SettledRow> rows = jdbc.query(SETTLED_SELECT + where + " order by v.settled_at desc limit :limit offset :offset",
                p, (rs, i) -> settledRow(rs));
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    public List<SettledRow> allSettled(ReportFilter filter, String outcomeClass, int max) {
        MapSqlParameterSource p = ReportSql.params();
        String where = ReportSql.where("v", "v.settled_at", filter, p) + classFilter(outcomeClass, p);
        p.addValue("limit", max);
        return jdbc.query(SETTLED_SELECT + where + " order by v.settled_at desc limit :limit", p, (rs, i) -> settledRow(rs));
    }

    public PageResponse<RejectionRow> rejections(ReportFilter filter, String classification, int page, int size) {
        MapSqlParameterSource p = ReportSql.params();
        String where = rejectionWhere(filter, classification, p);
        Long total = jdbc.queryForObject("select count(*) from v_decision d" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<RejectionRow> rows = jdbc.query(REJECTION_SELECT + where + " order by d.created_at desc limit :limit offset :offset",
                p, (rs, i) -> rejectionRow(rs));
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    public List<RejectionRow> allRejections(ReportFilter filter, String classification, int max) {
        MapSqlParameterSource p = ReportSql.params();
        String where = rejectionWhere(filter, classification, p);
        p.addValue("limit", max);
        return jdbc.query(REJECTION_SELECT + where + " order by d.created_at desc limit :limit", p, (rs, i) -> rejectionRow(rs));
    }

    /** Rejection outcome counts grouped by a dimension: reason, strategy or runner. */
    public Map<String, Map<String, Long>> rejectionBreakdown(ReportFilter filter, String dimension) {
        String column = switch (dimension) {
            case "strategy" -> "d.strategy_code";
            case "runner" -> "coalesce(r.runner_name, d.selection_id::text)";
            default -> "d.rejection_reason";
        };
        MapSqlParameterSource p = ReportSql.params();
        String where = rejectionWhere(filter, null, p);
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        jdbc.query("select " + column + " as k, coalesce(d.outcome_classification, 'AWAITING_RESULT') as c, count(*) as n"
                + " from v_decision d left join market_runner r on r.market_id = d.market_id and r.selection_id = d.selection_id"
                + where + " group by 1, 2 order by 1", p, rs -> {
                    result.computeIfAbsent(rs.getString("k") == null ? "UNSPECIFIED" : rs.getString("k"),
                            k -> new LinkedHashMap<>()).put(rs.getString("c"), rs.getLong("n"));
                });
        return result;
    }

    /** Exposure and realized returns by side, market type, mode, configuration and odds band (SETTLED units). */
    public List<BreakdownRow> breakdown(ReportFilter filter) {
        MapSqlParameterSource p = ReportSql.params();
        String where = ReportSql.where("v", "v.settled_at", filter, p) + " and v.bet_status = 'SETTLED'";
        return jdbc.query("select v.side, v.market_type, v.mode, coalesce(c.name, 'No configuration') as configuration_name, "
                + ODDS_BAND + " as odds_band, count(*) as units, coalesce(sum(v.size_settled), 0) as matched_stake,"
                + " coalesce(sum(case when v.side = 'LAY' then v.size_settled * (coalesce(v.price_matched, v.average_price_matched) - 1)"
                + " else 0 end), 0) as lay_liability, coalesce(sum(v.profit), 0) as profit"
                + " from v_settled_bet v left join bot_configuration c on c.id = v.configuration_id" + where
                + " group by 1, 2, 3, 4, 5 order by 1, 2, 3, 4, 5", p,
                (rs, i) -> new BreakdownRow(rs.getString("side"), rs.getString("market_type"), rs.getString("mode"),
                        rs.getString("configuration_name"), rs.getString("odds_band"), rs.getLong("units"),
                        rs.getBigDecimal("matched_stake"), rs.getBigDecimal("lay_liability"), rs.getBigDecimal("profit")));
    }

    private static String rejectionWhere(ReportFilter filter, String classification, MapSqlParameterSource p) {
        String where = ReportSql.where("d", "d.created_at", filter, p) + " and d.status = 'REJECTED'";
        if ("AWAITING_RESULT".equals(classification)) {
            return where + " and d.outcome_classification is null";
        }
        if (classification != null) {
            p.addValue("classification", classification);
            return where + " and d.outcome_classification = :classification";
        }
        return where;
    }

    private static String classFilter(String outcomeClass, MapSqlParameterSource p) {
        if (outcomeClass == null) {
            return "";
        }
        p.addValue("outcomeClass", outcomeClass);
        return " and v.outcome_class = :outcomeClass";
    }

    private static SettledRow settledRow(ResultSet rs) throws SQLException {
        return new SettledRow(rs.getString("bet_id"), rs.getString("bet_status"), rs.getInt("revision"),
                rs.getBoolean("corrected"), rs.getString("outcome_class"), ts(rs, "settled_at"), rs.getString("mode"),
                (UUID) rs.getObject("configuration_id"), rs.getString("configuration_name"), rs.getString("market_id"),
                rs.getString("venue"), ts(rs, "market_start_time"), rs.getString("market_type"), rs.getLong("selection_id"),
                rs.getString("runner_name"), rs.getString("side"), rs.getBigDecimal("requested_price"),
                rs.getBigDecimal("price_matched"), (Boolean) rs.getObject("price_reduced"), rs.getBigDecimal("size_settled"),
                rs.getBigDecimal("profit"), (UUID) rs.getObject("recommendation_id"));
    }

    private static RejectionRow rejectionRow(ResultSet rs) throws SQLException {
        return new RejectionRow((UUID) rs.getObject("recommendation_id"), ts(rs, "created_at"),
                ts(rs, "approval_decided_at"), rs.getString("rejection_reason"), (UUID) rs.getObject("configuration_id"),
                rs.getString("configuration_name"), rs.getString("strategy_code"), rs.getString("market_id"),
                rs.getString("venue"), ts(rs, "market_start_time"), rs.getString("market_type"), rs.getLong("selection_id"),
                rs.getString("runner_name"), rs.getString("side"), rs.getBigDecimal("proposed_price"),
                rs.getBigDecimal("proposed_stake"), rs.getString("outcome_classification"), rs.getString("runner_result"),
                (Boolean) rs.getObject("dead_heat"));
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
