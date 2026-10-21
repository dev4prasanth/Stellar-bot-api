package com.stellartrack.reporting.application;

import com.stellartrack.reporting.domain.MetricDefinitions;
import com.stellartrack.reporting.domain.ReportFilter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KPI totals for a period. Each stage is counted on its own identifier and date basis; a recommendation, a
 * submission, a matched fill and a settled unit are never equated. Dashboard tiles use this same service.
 */
@Service
@Transactional(readOnly = true)
public class ReportSummaryService {

    public record Decisions(long evaluated, Map<String, Long> byStatus, Map<String, Long> bySource,
            Map<String, Long> rejectedByReason) {
    }

    public record Orders(long placed, long matched, long unmatched, long unresolved, long exchangeRejected,
            long technicalFailures, long unknownOutcomePending, long notFoundAfterReconciliation,
            BigDecimal submittedStake) {
    }

    public record Settlements(long won, long wonReduced, long lost, long settledZero, long voided, long unmatched,
            long corrected, BigDecimal winRate, long winRateDenominator) {
    }

    /** Hypothetical selection-outcome analysis of administrator-rejected proposals. */
    public record RejectedAnalysis(long rejectedTotal, long favorable, long unfavorable, long voided,
            long notEvaluable, long awaitingResult, BigDecimal favorableRate, long favorableRateDenominator,
            BigDecimal coverage) {
    }

    public record Realized(BigDecimal matchedStake, BigDecimal layLiability, BigDecimal grossPnl, BigDecimal commission,
            BigDecimal netPnl, boolean commissionAttributable, String commissionNote) {
    }

    public record Summary(ReportFilter filter, String definitionsVersion, Instant generatedAt, Decisions decisions,
            Orders orders, Settlements settlements, RejectedAnalysis rejected, Realized realized) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ReportSummaryService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Summary summary(ReportFilter filter) {
        return new Summary(filter, MetricDefinitions.VERSION, clock.instant(), decisions(filter), orders(filter),
                settlements(filter), rejected(filter), realized(filter));
    }

    Decisions decisions(ReportFilter filter) {
        MapSqlParameterSource p = ReportSql.params();
        String where = ReportSql.where("d", "d.created_at", filter, p);
        Map<String, Long> byStatus = grouped("select d.status as k, count(*) as n from v_decision d" + where + " group by d.status", p);
        Map<String, Long> bySource = grouped("select d.selection_source as k, count(*) as n from v_decision d" + where
                + " group by d.selection_source", p);
        Map<String, Long> byReason = grouped("select d.rejection_reason as k, count(*) as n from v_decision d" + where
                + " and d.status = 'REJECTED' group by d.rejection_reason", p);
        long evaluated = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new Decisions(evaluated, byStatus, bySource, byReason);
    }

    Orders orders(ReportFilter filter) {
        MapSqlParameterSource p = ReportSql.params();
        String where = ReportSql.where("oi", "oi.created_at", filter, p);
        Map<String, Long> byStatus = grouped("select oi.status as k, count(*) as n from order_intent oi" + where
                + " group by oi.status", p);
        Map<String, Object> placed = jdbc.queryForMap("""
                select count(*) filter (where coalesce(b.size_matched, 0) > 0) as matched,
                       count(*) filter (where coalesce(b.size_matched, 0) = 0) as unmatched,
                       count(*) filter (where coalesce(b.size_matched, 0) > 0 and not exists (
                           select 1 from settlement s where s.bet_id = b.bet_id and s.is_current
                             and s.bet_status in ('SETTLED', 'VOIDED'))) as unresolved,
                       coalesce(sum(oi.requested_size), 0) as stake
                from order_intent oi left join bet_order b on b.intent_id = oi.id""" + where + " and oi.status = 'PLACED'", p);
        return new Orders(byStatus.getOrDefault("PLACED", 0L), number(placed.get("matched")), number(placed.get("unmatched")),
                number(placed.get("unresolved")), byStatus.getOrDefault("EXCHANGE_REJECTED", 0L),
                byStatus.getOrDefault("TECHNICAL_FAILURE", 0L), byStatus.getOrDefault("UNKNOWN_OUTCOME", 0L),
                byStatus.getOrDefault("NOT_FOUND_AFTER_RECONCILIATION", 0L), (BigDecimal) placed.get("stake"));
    }

    Settlements settlements(ReportFilter filter) {
        MapSqlParameterSource p = ReportSql.params();
        String where = ReportSql.where("v", "v.settled_at", filter, p);
        Map<String, Long> byClass = grouped("select v.outcome_class as k, count(*) as n from v_settled_bet v" + where
                + " group by v.outcome_class", p);
        Long corrected = jdbc.queryForObject("select count(*) from v_settled_bet v" + where + " and v.corrected", p, Long.class);
        long won = byClass.getOrDefault("WON", 0L);
        long wonReduced = byClass.getOrDefault("WON_REDUCED", 0L);
        long lost = byClass.getOrDefault("LOST", 0L);
        long denominator = won + wonReduced + lost;
        return new Settlements(won, wonReduced, lost, byClass.getOrDefault("SETTLED_ZERO", 0L),
                byClass.getOrDefault("VOID", 0L), byClass.getOrDefault("UNMATCHED", 0L), corrected == null ? 0 : corrected,
                ratio(won + wonReduced, denominator), denominator);
    }

    RejectedAnalysis rejected(ReportFilter filter) {
        MapSqlParameterSource p = ReportSql.params();
        String where = ReportSql.where("d", "d.created_at", filter, p);
        Map<String, Long> byClass = grouped("select coalesce(d.outcome_classification, 'AWAITING_RESULT') as k, count(*) as n"
                + " from v_decision d" + where + " and d.status = 'REJECTED' group by 1", p);
        long favorable = byClass.getOrDefault("FAVORABLE", 0L);
        long unfavorable = byClass.getOrDefault("UNFAVORABLE", 0L);
        long voided = byClass.getOrDefault("VOID", 0L);
        long notEvaluable = byClass.getOrDefault("NOT_EVALUABLE", 0L);
        long awaiting = byClass.getOrDefault("AWAITING_RESULT", 0L);
        long total = favorable + unfavorable + voided + notEvaluable + awaiting;
        return new RejectedAnalysis(total, favorable, unfavorable, voided, notEvaluable, awaiting,
                ratio(favorable, favorable + unfavorable), favorable + unfavorable,
                ratio(total - awaiting, total));
    }

    Realized realized(ReportFilter filter) {
        MapSqlParameterSource p = ReportSql.params();
        String where = ReportSql.where("v", "v.settled_at", filter, p);
        Map<String, Object> totals = jdbc.queryForMap("""
                select coalesce(sum(v.size_settled) filter (where v.bet_status = 'SETTLED'), 0) as matched_stake,
                       coalesce(sum(case when v.side = 'LAY' and v.bet_status = 'SETTLED'
                                         then v.size_settled * (coalesce(v.price_matched, v.average_price_matched) - 1)
                                         else 0 end), 0) as lay_liability,
                       coalesce(sum(v.profit) filter (where v.bet_status = 'SETTLED'), 0) as gross
                from v_settled_bet v""" + where, p);
        BigDecimal gross = (BigDecimal) totals.get("gross");
        boolean attributable = filter.configurationId() == null && filter.mode() == null && filter.side() == null
                && filter.marketType() == null;
        BigDecimal commission = null;
        if (attributable) {
            commission = jdbc.queryForObject("""
                    select coalesce(sum(ms.commission), 0) from market_settlement ms
                    where ms.is_current and ms.market_id in (select distinct v.market_id from v_settled_bet v""" + where + ")",
                    p, BigDecimal.class);
        }
        return new Realized((BigDecimal) totals.get("matched_stake"),
                ((BigDecimal) totals.get("lay_liability")).setScale(2, RoundingMode.HALF_EVEN), gross, commission,
                commission == null ? null : gross.subtract(commission), attributable,
                attributable ? "Market-level commission for markets with this application's bets"
                        : "Commission is market-level and cannot be attributed to a configuration, mode, side or market type");
    }

    private Map<String, Long> grouped(String sql, MapSqlParameterSource params) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, params, rs -> {
            result.put(rs.getString("k") == null ? "UNSPECIFIED" : rs.getString("k"), rs.getLong("n"));
        });
        return result;
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    static BigDecimal ratio(long numerator, long denominator) {
        return denominator == 0 ? null
                : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_EVEN);
    }
}
