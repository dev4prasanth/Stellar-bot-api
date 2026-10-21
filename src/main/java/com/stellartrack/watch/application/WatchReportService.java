package com.stellartrack.watch.application;

import com.stellartrack.common.money.MoneyPolicy;
import com.stellartrack.reporting.application.CsvWriter;
import com.stellartrack.reporting.application.ReportSql;
import com.stellartrack.reporting.application.ReportTrendService.Granularity;
import com.stellartrack.reporting.domain.ReportFilter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Watch Mode reports, computed from {@code v_watch_bet} only (never from live orders). Filters reuse the live
 * reporting filter ({@link ReportSql}); the date basis is the placement date. Definitions:
 * <ul>
 *   <li>Win rate = won ÷ (won + lost); ROI = net P&amp;L ÷ matched stake (BACK stake, LAY liability).</li>
 *   <li>Net = gross − commission; best case assumes every bet fully matched at its requested price.</li>
 *   <li>Max drawdown = largest fall of cumulative net P&amp;L from a previous peak, in settlement order.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class WatchReportService {

    public static final String DEFINITIONS_VERSION = "WATCH_REPORTS_V1";

    /** Scope of a Watch report: optionally one session, plus the shared period/configuration/side/type filter. */
    public record WatchReportFilter(UUID sessionId, ReportFilter period) {
    }

    public record Totals(long bets, long matched, long open, long won, long lost, long voided, long unmatched,
            long unresolved, BigDecimal matchedStake, BigDecimal grossProfit, BigDecimal commission, BigDecimal netProfit,
            BigDecimal bestCaseNetProfit, BigDecimal winRate, BigDecimal roi, BigDecimal averageMatchedPrice,
            BigDecimal matchedRate) {
    }

    public record TrendPoint(LocalDate bucket, long won, long lost, BigDecimal netProfit, BigDecimal bestCaseNetProfit,
            BigDecimal cumulativeNet, BigDecimal cumulativeBestCase) {
    }

    /** One group of a breakdown (configuration, odds band, side, ...). */
    public record Breakdown(String key, long bets, long won, long lost, BigDecimal matchedStake, BigDecimal netProfit,
            BigDecimal winRate, BigDecimal roi) {
    }

    public record Staking(BigDecimal averageStake, BigDecimal maxStake, int longestWinStreak, int longestLossStreak,
            BigDecimal maxDrawdown, List<Breakdown> byStakeBand, List<Breakdown> byStakingMethod) {
    }

    public record Matching(BigDecimal matchedRate, BigDecimal averageSlippageTicks, Long averageDataDelayMs,
            List<Breakdown> unmatchedReasons) {
    }

    public record Report(WatchReportFilter filter, String definitionsVersion, Instant generatedAt, Totals totals,
            List<TrendPoint> trend, Map<String, List<Breakdown>> breakdowns, Staking staking, Matching matching) {
    }

    private static final String MATCHED_STAKE = "case when v.side = 'BACK' then v.size_matched "
            + "else v.size_matched * (coalesce(v.average_price_matched, v.requested_price) - 1) end";

    private static final String GROUP_AGGREGATES = """
            count(*) as bets,
            count(*) filter (where v.result_status = 'WON') as won,
            count(*) filter (where v.result_status = 'LOST') as lost,
            coalesce(sum(%s), 0) as matched_stake,
            coalesce(sum(v.net_profit), 0) as net_profit""".formatted(MATCHED_STAKE);

    /** Breakdown dimensions: name → grouping expression over {@code v_watch_bet v}. */
    private static final Map<String, String> DIMENSIONS = Map.of(
            "configuration", "v.configuration_name",
            "side", "v.side",
            "marketType", "v.market_type",
            "venue", "coalesce(v.venue, 'Unknown')",
            "oddsBand", """
                    case when v.requested_price < 2 then '1.01–1.99' when v.requested_price < 4 then '2.00–3.95'
                         when v.requested_price < 8 then '4.0–7.8' when v.requested_price < 16 then '8.0–15.5'
                         else '16+' end""",
            "priceRank", "coalesce('Rank ' || v.price_rank, 'Unranked')");

    private static final String STAKE_BAND = """
            case when v.requested_size < 5 then '< 5' when v.requested_size < 20 then '5–19.99'
                 when v.requested_size < 50 then '20–49.99' when v.requested_size < 100 then '50–99.99' else '100+' end""";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public WatchReportService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Report report(WatchReportFilter filter, Granularity granularity) {
        Map<String, List<Breakdown>> breakdowns = new LinkedHashMap<>();
        DIMENSIONS.forEach((name, expression) -> breakdowns.put(name, breakdown(filter, expression, "")));
        return new Report(filter, DEFINITIONS_VERSION, clock.instant(), totals(filter), trend(filter, granularity),
                breakdowns, staking(filter), matching(filter));
    }

    public Totals totals(WatchReportFilter filter) {
        MapSqlParameterSource p = ReportSql.params();
        String where = where(filter, p);
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) as bets,
                       count(*) filter (where v.size_matched > 0) as matched,
                       count(*) filter (where v.result_status = 'OPEN') as open,
                       count(*) filter (where v.result_status = 'WON') as won,
                       count(*) filter (where v.result_status = 'LOST') as lost,
                       count(*) filter (where v.result_status = 'VOID') as voided,
                       count(*) filter (where v.display_status = 'UNMATCHED') as unmatched,
                       count(*) filter (where v.result_status = 'UNRESOLVED') as unresolved,
                       coalesce(sum(""" + MATCHED_STAKE + """
                ), 0) as matched_stake,
                       coalesce(sum(v.gross_profit), 0) as gross_profit,
                       coalesce(sum(v.commission), 0) as commission,
                       coalesce(sum(v.net_profit), 0) as net_profit,
                       coalesce(sum(v.best_case_net_profit), 0) as best_case_net_profit,
                       avg(v.average_price_matched) as average_matched_price
                from v_watch_bet v""" + where, p);
        long won = n(row, "won");
        long lost = n(row, "lost");
        long bets = n(row, "bets");
        BigDecimal stake = money(row, "matched_stake");
        BigDecimal net = money(row, "net_profit");
        BigDecimal avgPrice = (BigDecimal) row.get("average_matched_price");
        return new Totals(bets, n(row, "matched"), n(row, "open"), won, lost, n(row, "voided"), n(row, "unmatched"),
                n(row, "unresolved"), stake, money(row, "gross_profit"), money(row, "commission"), net,
                money(row, "best_case_net_profit"), ratio(won, won + lost), ratio(net, stake),
                avgPrice == null ? null : avgPrice.setScale(2, RoundingMode.HALF_EVEN), ratio(n(row, "matched"), bets));
    }

    public List<TrendPoint> trend(WatchReportFilter filter, Granularity granularity) {
        MapSqlParameterSource p = ReportSql.params();
        String where = where(filter, p);
        p.addValue("unit", granularity.sqlUnit()).addValue("zone", filter.period().zone().getId());
        List<TrendPoint> points = new ArrayList<>();
        BigDecimal[] cumulative = {BigDecimal.ZERO, BigDecimal.ZERO};
        jdbc.query("""
                select date_trunc(:unit, v.placed_at at time zone :zone)::date as bucket,
                       count(*) filter (where v.result_status = 'WON') as won,
                       count(*) filter (where v.result_status = 'LOST') as lost,
                       coalesce(sum(v.net_profit), 0) as net_profit,
                       coalesce(sum(v.best_case_net_profit), 0) as best_case_net_profit
                from v_watch_bet v""" + where + " group by 1 order by 1", p, rs -> {
            BigDecimal net = rs.getBigDecimal("net_profit");
            BigDecimal best = rs.getBigDecimal("best_case_net_profit");
            cumulative[0] = cumulative[0].add(net);
            cumulative[1] = cumulative[1].add(best);
            points.add(new TrendPoint(rs.getDate("bucket").toLocalDate(), rs.getLong("won"), rs.getLong("lost"), net, best,
                    cumulative[0], cumulative[1]));
        });
        return points;
    }

    public Staking staking(WatchReportFilter filter) {
        MapSqlParameterSource p = ReportSql.params();
        String where = where(filter, p);
        Map<String, Object> stakes = jdbc.queryForMap("select avg(v.requested_size) as average_stake, "
                + "max(v.requested_size) as max_stake from v_watch_bet v" + where, p);
        List<BigDecimal> sequence = jdbc.queryForList("select v.net_profit from v_watch_bet v" + where
                + " and v.result_status in ('WON', 'LOST') order by v.settled_at, v.placed_at", p, BigDecimal.class);
        BigDecimal average = (BigDecimal) stakes.get("average_stake");
        return new Staking(average == null ? null : MoneyPolicy.amount(average), (BigDecimal) stakes.get("max_stake"),
                longestStreak(sequence, 1), longestStreak(sequence, -1), maxDrawdown(sequence),
                breakdown(filter, STAKE_BAND, ""), breakdown(filter, "v.staking_method", ""));
    }

    public Matching matching(WatchReportFilter filter) {
        MapSqlParameterSource p = ReportSql.params();
        String where = where(filter, p);
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) as bets, count(*) filter (where v.size_matched > 0) as matched,
                       avg(v.slippage_ticks) as slippage, avg(v.data_delay_ms) as delay
                from v_watch_bet v""" + where, p);
        BigDecimal slippage = (BigDecimal) row.get("slippage");
        BigDecimal delay = (BigDecimal) row.get("delay");
        return new Matching(ratio(n(row, "matched"), n(row, "bets")),
                slippage == null ? null : slippage.setScale(2, RoundingMode.HALF_EVEN),
                delay == null ? null : delay.longValue(),
                breakdown(filter, "coalesce(v.lapse_reason, 'Not recorded')", " and v.display_status = 'UNMATCHED'"));
    }

    /** CSV of every simulated bet in scope; every row is tagged SIMULATED. */
    public String export(WatchReportFilter filter) {
        MapSqlParameterSource p = ReportSql.params();
        String where = where(filter, p);
        CsvWriter csv = new CsvWriter().row(List.of("kind", "bet_id", "session_id", "configuration", "placed_at", "venue",
                "race", "runner", "side", "market_type", "requested_size", "requested_price", "size_matched",
                "average_price_matched", "status", "gross_profit", "commission", "net_profit", "best_case_net_profit",
                "definitions_version"));
        jdbc.query("select * from v_watch_bet v" + where + " order by v.placed_at", p, rs -> {
            csv.row(Arrays.asList("SIMULATED", rs.getString("bet_id"), rs.getString("session_id"),
                    rs.getString("configuration_name"), rs.getTimestamp("placed_at").toInstant().toString(),
                    rs.getString("venue"), rs.getString("market_name"), rs.getString("runner_name"), rs.getString("side"),
                    rs.getString("market_type"), rs.getBigDecimal("requested_size"), rs.getBigDecimal("requested_price"),
                    rs.getBigDecimal("size_matched"), rs.getBigDecimal("average_price_matched"),
                    rs.getString("display_status"), rs.getBigDecimal("gross_profit"), rs.getBigDecimal("commission"),
                    rs.getBigDecimal("net_profit"), rs.getBigDecimal("best_case_net_profit"), DEFINITIONS_VERSION));
        });
        return csv.build();
    }

    private List<Breakdown> breakdown(WatchReportFilter filter, String keyExpression, String extraWhere) {
        MapSqlParameterSource p = ReportSql.params();
        String where = where(filter, p);
        return jdbc.query("select " + keyExpression + " as key, " + GROUP_AGGREGATES + " from v_watch_bet v" + where
                + extraWhere + " group by 1 order by count(*) desc, 1", p, (rs, i) -> {
            long won = rs.getLong("won");
            long lost = rs.getLong("lost");
            BigDecimal stake = rs.getBigDecimal("matched_stake");
            BigDecimal net = rs.getBigDecimal("net_profit");
            return new Breakdown(rs.getString("key"), rs.getLong("bets"), won, lost, MoneyPolicy.amount(stake),
                    MoneyPolicy.amount(net), ratio(won, won + lost), ratio(net, stake));
        });
    }

    private static String where(WatchReportFilter filter, MapSqlParameterSource p) {
        String where = ReportSql.where("v", "v.placed_at", filter.period(), p);
        if (filter.sessionId() == null) {
            return where;
        }
        p.addValue("sessionId", filter.sessionId());
        return where + " and v.session_id = :sessionId";
    }

    static int longestStreak(List<BigDecimal> sequence, int sign) {
        int longest = 0;
        int current = 0;
        for (BigDecimal value : sequence) {
            current = value.signum() == sign ? current + 1 : 0;
            longest = Math.max(longest, current);
        }
        return longest;
    }

    static BigDecimal maxDrawdown(List<BigDecimal> sequence) {
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal drawdown = BigDecimal.ZERO;
        for (BigDecimal value : sequence) {
            cumulative = cumulative.add(value);
            peak = peak.max(cumulative);
            drawdown = drawdown.max(peak.subtract(cumulative));
        }
        return MoneyPolicy.amount(drawdown);
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0 ? null
                : numerator.divide(denominator, 4, RoundingMode.HALF_EVEN);
    }

    private static long n(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static BigDecimal money(Map<String, Object> row, String key) {
        return MoneyPolicy.amount(MoneyPolicy.nullToZero((BigDecimal) row.get(key)));
    }

    /** Placement-date window covering a whole session, for reports scoped to one session. */
    public static ReportFilter sessionPeriod(ReportFilter base, Instant startedAt, Instant stoppedAt, Instant now) {
        Instant end = stoppedAt == null ? now : stoppedAt.plusSeconds(1);
        return new ReportFilter(startedAt, end, base.zone(), base.configurationId(), null, base.side(), base.marketType());
    }
}
