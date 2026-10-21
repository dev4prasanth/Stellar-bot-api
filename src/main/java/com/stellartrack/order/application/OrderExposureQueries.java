package com.stellartrack.order.application;

import com.stellartrack.order.risk.EntryHistory;
import com.stellartrack.common.jdbc.JdbcAggregates;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only aggregates used by pre-trade risk rules.
 *
 * <p>Open exposure is a conservative worst case per unsettled order: BACK = matched + remaining stake (doubled for
 * EACH_WAY, whose stake is per part);
 * LAY = matched × (avg matched price − 1) + remaining × (requested price − 1). Intents not yet confirmed by
 * Betfair (pending, submitting, unknown outcome) count at their full requested exposure. Offsetting
 * hedges are not netted, so the figure never understates risk.
 */
@Component
public class OrderExposureQueries implements EntryHistory {

    private static final String LIVE_STATUSES = "('PENDING_SUBMISSION', 'SUBMITTING', 'PLACED', 'UNKNOWN_OUTCOME')";

    private static final String EXPOSURE_EXPR = """
            case when oi.side = 'BACK'
                 then (coalesce(b.size_matched, 0) + coalesce(b.size_remaining, case when b.bet_id is null then oi.requested_size else 0 end))
                      * case when oi.market_type = 'EACH_WAY' then 2 else 1 end
                 else coalesce(b.size_matched, 0) * (coalesce(b.average_price_matched, oi.requested_price) - 1)
                      + coalesce(b.size_remaining, case when b.bet_id is null then oi.requested_size else 0 end) * (oi.requested_price - 1)
            end""";

    private static final String OPEN_FROM = """
            from order_intent oi
            left join bet_order b on b.intent_id = oi.id
            join market m on m.market_id = oi.market_id
            where oi.status in """ + LIVE_STATUSES + """

              and (b.bet_id is null or not exists (select 1 from settlement s where s.bet_id = b.bet_id and s.is_current and s.bet_status in ('SETTLED', 'VOIDED')))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OrderExposureQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BigDecimal openExposureForConfiguration(UUID configurationId) {
        return JdbcAggregates.sum(jdbc, "select coalesce(sum(" + EXPOSURE_EXPR + "), 0) " + OPEN_FROM + " and oi.configuration_id = :cid",
                new MapSqlParameterSource("cid", configurationId));
    }

    /** Exposure on one race (event + scheduled start) across WIN and PLACE markets, for a configuration. */
    @Override
    public BigDecimal raceExposureForConfiguration(UUID configurationId, String eventId, Instant raceStart) {
        return JdbcAggregates.sum(jdbc, "select coalesce(sum(" + EXPOSURE_EXPR + "), 0) " + OPEN_FROM
                        + " and oi.configuration_id = :cid and m.event_id = :eventId and m.market_start_time = :start",
                new MapSqlParameterSource("cid", configurationId).addValue("eventId", eventId)
                        .addValue("start", Timestamp.from(raceStart)));
    }

    public BigDecimal totalApplicationOpenExposure() {
        return JdbcAggregates.sum(jdbc, "select coalesce(sum(" + EXPOSURE_EXPR + "), 0) " + OPEN_FROM, new MapSqlParameterSource());
    }

    @Override
    public int entriesForRace(UUID configurationId, String eventId, Instant raceStart) {
        return JdbcAggregates.count(jdbc, """
                select count(*) from order_intent oi join market m on m.market_id = oi.market_id
                where oi.purpose = 'ENTRY' and oi.configuration_id = :cid and m.event_id = :eventId
                  and m.market_start_time = :start and oi.status in """ + LIVE_STATUSES,
                new MapSqlParameterSource("cid", configurationId).addValue("eventId", eventId)
                        .addValue("start", Timestamp.from(raceStart)));
    }

    @Override
    public int entriesSince(UUID configurationId, Instant since) {
        return JdbcAggregates.count(jdbc, """
                select count(*) from order_intent oi
                where oi.purpose = 'ENTRY' and oi.configuration_id = :cid and oi.created_at >= :since
                  and oi.status in """ + LIVE_STATUSES,
                new MapSqlParameterSource("cid", configurationId).addValue("since", Timestamp.from(since)));
    }

    /** Current settled profit of the configuration's entries placed since {@code since} (voids contribute 0). */
    @Override
    public BigDecimal settledProfitSince(UUID configurationId, Instant since) {
        return JdbcAggregates.sum(jdbc, """
                select coalesce(sum(s.profit), 0) from settlement s
                join bet_order b on b.bet_id = s.bet_id join order_intent oi on oi.id = b.intent_id
                where s.is_current and s.bet_status = 'SETTLED' and oi.configuration_id = :cid and oi.created_at >= :since
                """, new MapSqlParameterSource("cid", configurationId).addValue("since", Timestamp.from(since)));
    }

    /** Signs of settled profits (+1 win, -1 loss), most recent first; zero-profit and voided bets are skipped. */
    @Override
    public List<Integer> recentOutcomeSigns(UUID configurationId, Instant since, int limit) {
        return jdbc.queryForList("""
                select sign(s.profit)::int from settlement s
                join bet_order b on b.bet_id = s.bet_id join order_intent oi on oi.id = b.intent_id
                where s.is_current and s.bet_status = 'SETTLED' and s.profit <> 0
                  and oi.configuration_id = :cid and oi.created_at >= :since
                order by s.settled_at desc limit :limit
                """, new MapSqlParameterSource("cid", configurationId).addValue("since", Timestamp.from(since))
                .addValue("limit", limit), Integer.class);
    }

    /** Settled WIN/LOSS sequence in settlement order, used to advance staking plans. */
    @Override
    public List<Integer> outcomeSignsAscending(UUID configurationId) {
        return jdbc.queryForList("""
                select sign(s.profit)::int from settlement s
                join bet_order b on b.bet_id = s.bet_id join order_intent oi on oi.id = b.intent_id
                where s.is_current and s.bet_status = 'SETTLED' and s.profit <> 0 and oi.configuration_id = :cid
                  and oi.purpose = 'ENTRY'
                order by s.settled_at asc
                """, new MapSqlParameterSource("cid", configurationId), Integer.class);
    }
}
