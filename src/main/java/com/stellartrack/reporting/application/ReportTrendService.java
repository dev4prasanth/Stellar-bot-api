package com.stellartrack.reporting.application;

import com.stellartrack.reporting.domain.ReportFilter;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dated chart series. Each series keeps its own date basis (placement, settlement or decision date), so the
 * series are comparable over time but are not stages of one cohort.
 */
@Service
@Transactional(readOnly = true)
public class ReportTrendService {

    public enum Granularity {
        DAY("day"),
        WEEK("week"),
        MONTH("month");

        private final String sqlUnit;

        Granularity(String sqlUnit) {
            this.sqlUnit = sqlUnit;
        }

        /** The PostgreSQL {@code date_trunc} unit. */
        public String sqlUnit() {
            return sqlUnit;
        }
    }

    public record TrendPoint(LocalDate bucket, long placed, long won, long lost, long manuallyRejected,
            BigDecimal grossPnl, BigDecimal commission, BigDecimal netPnl, BigDecimal cumulativeNetPnl) {
    }

    public record TrendSeries(Granularity granularity, String timeZone, boolean commissionAttributable,
            Map<String, String> dateBasis, List<TrendPoint> points) {
    }

    public record FunnelStage(String stage, long count, String parent) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ReportTrendService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TrendSeries trends(ReportFilter filter, Granularity granularity) {
        Map<LocalDate, long[]> counts = new TreeMap<>();
        Map<LocalDate, BigDecimal[]> money = new TreeMap<>();
        for (LocalDate bucket = bucketOf(filter.from().atZone(filter.zone()).toLocalDate(), granularity);
                !bucket.isAfter(filter.to().atZone(filter.zone()).toLocalDate()); bucket = next(bucket, granularity)) {
            counts.put(bucket, new long[4]);
            money.put(bucket, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        }
        series("order_intent oi", "oi", "oi.created_at", " and oi.status = 'PLACED'", "count(*)", filter, granularity)
                .forEach((b, v) -> counts.computeIfAbsent(b, k -> new long[4])[0] = v.longValue());
        series("v_settled_bet v", "v", "v.settled_at", " and v.outcome_class in ('WON', 'WON_REDUCED')", "count(*)", filter, granularity)
                .forEach((b, v) -> counts.computeIfAbsent(b, k -> new long[4])[1] = v.longValue());
        series("v_settled_bet v", "v", "v.settled_at", " and v.outcome_class = 'LOST'", "count(*)", filter, granularity)
                .forEach((b, v) -> counts.computeIfAbsent(b, k -> new long[4])[2] = v.longValue());
        series("v_decision d", "d", "d.approval_decided_at", " and d.status = 'REJECTED'", "count(*)", filter, granularity)
                .forEach((b, v) -> counts.computeIfAbsent(b, k -> new long[4])[3] = v.longValue());
        series("v_settled_bet v", "v", "v.settled_at", " and v.bet_status = 'SETTLED'", "coalesce(sum(v.profit), 0)", filter, granularity)
                .forEach((b, v) -> money.computeIfAbsent(b, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO})[0] = v);
        boolean attributable = filter.configurationId() == null && filter.mode() == null && filter.side() == null
                && filter.marketType() == null;
        if (attributable) {
            commissionSeries(filter, granularity).forEach((b, v) ->
                    money.computeIfAbsent(b, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO})[1] = v);
        }
        List<TrendPoint> points = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (LocalDate bucket : counts.keySet()) {
            long[] c = counts.get(bucket);
            BigDecimal[] m = money.getOrDefault(bucket, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal net = attributable ? m[0].subtract(m[1]) : null;
            if (net != null) {
                cumulative = cumulative.add(net);
            }
            points.add(new TrendPoint(bucket, c[0], c[1], c[2], c[3], m[0], attributable ? m[1] : null, net,
                    attributable ? cumulative : null));
        }
        return new TrendSeries(granularity, filter.zone().getId(), attributable, Map.of(
                "placed", "placement date", "won/lost/grossPnl", "settlement date",
                "manuallyRejected", "administrator decision date", "commission", "market settlement date"), points);
    }

    /** Branching cohort of recommendations created in the period; rejection is a branch, not a later stage. */
    public List<FunnelStage> funnel(ReportFilter filter) {
        MapSqlParameterSource p = ReportSql.params();
        String where = ReportSql.where("d", "d.created_at", filter, p);
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) as evaluated,
                       count(*) filter (where d.status = 'SKIPPED') as skipped,
                       count(*) filter (where d.requires_approval) as review,
                       count(*) filter (where d.status = 'PENDING_APPROVAL') as pending,
                       count(*) filter (where d.status = 'APPROVED') as approved,
                       count(*) filter (where d.status = 'REJECTED') as rejected,
                       count(*) filter (where d.status = 'EXPIRED') as expired,
                       count(*) filter (where d.status = 'ACCEPTED') as accepted,
                       count(*) filter (where d.submitted) as submitted,
                       count(*) filter (where d.matched) as matched,
                       count(*) filter (where exists (select 1 from v_settled_bet v where v.recommendation_id = d.recommendation_id
                                                      and v.bet_status in ('SETTLED', 'VOIDED'))) as settled
                from v_decision d""" + where, p);
        return List.of(
                new FunnelStage("Evaluated", n(row, "evaluated"), null),
                new FunnelStage("No bet (skipped)", n(row, "skipped"), "Evaluated"),
                new FunnelStage("Sent for review", n(row, "review"), "Evaluated"),
                new FunnelStage("Awaiting review", n(row, "pending"), "Sent for review"),
                new FunnelStage("Approved", n(row, "approved"), "Sent for review"),
                new FunnelStage("Rejected", n(row, "rejected"), "Sent for review"),
                new FunnelStage("Expired", n(row, "expired"), "Sent for review"),
                new FunnelStage("Accepted without review", n(row, "accepted"), "Evaluated"),
                new FunnelStage("Submitted", n(row, "submitted"), "Approved + Accepted"),
                new FunnelStage("Matched", n(row, "matched"), "Submitted"),
                new FunnelStage("Settled", n(row, "settled"), "Matched"));
    }

    private Map<LocalDate, BigDecimal> series(String from, String alias, String dateColumn, String extra, String aggregate,
            ReportFilter filter, Granularity granularity) {
        MapSqlParameterSource p = ReportSql.params();
        String where = ReportSql.where(alias, dateColumn, filter, p);
        p.addValue("unit", granularity.sqlUnit).addValue("zone", filter.zone().getId());
        String sql = "select date_trunc(:unit, " + dateColumn + " at time zone :zone)::date as bucket, " + aggregate
                + " as value from " + from + where + extra + " group by 1 order by 1";
        Map<LocalDate, BigDecimal> result = new TreeMap<>();
        jdbc.query(sql, p, rs -> {
            Date bucket = rs.getDate("bucket");
            result.put(bucket.toLocalDate(), rs.getBigDecimal("value"));
        });
        return result;
    }

    private Map<LocalDate, BigDecimal> commissionSeries(ReportFilter filter, Granularity granularity) {
        MapSqlParameterSource p = ReportSql.params();
        String where = ReportSql.where("v", "v.settled_at", filter, p);
        p.addValue("unit", granularity.sqlUnit).addValue("zone", filter.zone().getId());
        Map<LocalDate, BigDecimal> result = new TreeMap<>();
        jdbc.query("select date_trunc(:unit, ms.settled_at at time zone :zone)::date as bucket, coalesce(sum(ms.commission), 0) as value"
                + " from market_settlement ms where ms.is_current and ms.market_id in (select distinct v.market_id from v_settled_bet v"
                + where + ") group by 1 order by 1", p, rs -> {
                    result.put(rs.getDate("bucket").toLocalDate(), rs.getBigDecimal("value"));
                });
        return result;
    }

    static LocalDate bucketOf(LocalDate date, Granularity granularity) {
        return switch (granularity) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    private static LocalDate next(LocalDate bucket, Granularity granularity) {
        return switch (granularity) {
            case DAY -> bucket.plusDays(1);
            case WEEK -> bucket.plusWeeks(1);
            case MONTH -> bucket.plusMonths(1);
        };
    }

    private static long n(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).longValue();
    }
}
