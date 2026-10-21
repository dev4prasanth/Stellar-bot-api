package com.stellartrack.watch.application;

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
 * The simulated-bet history the risk rules and staking plans are evaluated against in Watch Mode. The scope is a
 * session configuration, so limits and stops count only that configuration's bets in that session — never live
 * orders, and never other sessions.
 *
 * <p>Open exposure follows the live definition: BACK = matched + remaining stake (doubled for EACH_WAY); LAY = matched × (avg matched price
 * − 1) + remaining × (requested price − 1), for every bet not yet settled.
 */
@Component
public class WatchExposureQueries implements EntryHistory {

    private static final String EXPOSURE_EXPR = """
            case when b.side = 'BACK'
                 then (b.size_matched + b.size_remaining) * case when b.market_type = 'EACH_WAY' then 2 else 1 end
                 else b.size_matched * (coalesce(b.average_price_matched, b.requested_price) - 1)
                      + b.size_remaining * (b.requested_price - 1)
            end""";

    private static final String OPEN_FROM = """
            from watch_bet b
            join market m on m.market_id = b.market_id
            where b.settled_at is null
            """;

    private static final String SETTLED_FROM = """
            from watch_bet b
            join watch_bet_settlement s on s.bet_id = b.id and s.is_current
            where s.result in ('WON', 'LOST') and s.net_profit <> 0
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public WatchExposureQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BigDecimal openExposureForConfiguration(UUID sessionConfigurationId) {
        return JdbcAggregates.sum(jdbc, "select coalesce(sum(" + EXPOSURE_EXPR + "), 0) " + OPEN_FROM + " and b.session_configuration_id = :scid",
                new MapSqlParameterSource("scid", sessionConfigurationId));
    }

    @Override
    public BigDecimal raceExposureForConfiguration(UUID sessionConfigurationId, String eventId, Instant raceStart) {
        return JdbcAggregates.sum(jdbc, "select coalesce(sum(" + EXPOSURE_EXPR + "), 0) " + OPEN_FROM
                        + " and b.session_configuration_id = :scid and m.event_id = :eventId and m.market_start_time = :start",
                race(sessionConfigurationId, eventId, raceStart));
    }

    /** Open simulated exposure of a whole session: the part of the virtual capital that is not available to bet. */
    public BigDecimal openExposureForSession(UUID sessionId) {
        return JdbcAggregates.sum(jdbc, "select coalesce(sum(" + EXPOSURE_EXPR + "), 0) " + OPEN_FROM + " and b.session_id = :sid",
                new MapSqlParameterSource("sid", sessionId));
    }

    @Override
    public int entriesForRace(UUID sessionConfigurationId, String eventId, Instant raceStart) {
        return JdbcAggregates.count(jdbc, """
                select count(*) from watch_bet b join market m on m.market_id = b.market_id
                where b.session_configuration_id = :scid and m.event_id = :eventId and m.market_start_time = :start
                """, race(sessionConfigurationId, eventId, raceStart));
    }

    @Override
    public int entriesSince(UUID sessionConfigurationId, Instant since) {
        return JdbcAggregates.count(jdbc, "select count(*) from watch_bet b where b.session_configuration_id = :scid and b.placed_at >= :since",
                new MapSqlParameterSource("scid", sessionConfigurationId).addValue("since", Timestamp.from(since)));
    }

    @Override
    public BigDecimal settledProfitSince(UUID sessionConfigurationId, Instant since) {
        return JdbcAggregates.sum(jdbc, "select coalesce(sum(s.net_profit), 0) " + SETTLED_FROM
                        + " and b.session_configuration_id = :scid and b.placed_at >= :since",
                new MapSqlParameterSource("scid", sessionConfigurationId).addValue("since", Timestamp.from(since)));
    }

    @Override
    public List<Integer> recentOutcomeSigns(UUID sessionConfigurationId, Instant since, int limit) {
        return jdbc.queryForList("select sign(s.net_profit)::int " + SETTLED_FROM
                        + " and b.session_configuration_id = :scid and b.placed_at >= :since order by s.settled_at desc limit :limit",
                new MapSqlParameterSource("scid", sessionConfigurationId).addValue("since", Timestamp.from(since))
                        .addValue("limit", limit), Integer.class);
    }

    @Override
    public List<Integer> outcomeSignsAscending(UUID sessionConfigurationId) {
        return jdbc.queryForList("select sign(s.net_profit)::int " + SETTLED_FROM
                        + " and b.session_configuration_id = :scid order by s.settled_at asc, b.placed_at asc",
                new MapSqlParameterSource("scid", sessionConfigurationId), Integer.class);
    }

    private static MapSqlParameterSource race(UUID sessionConfigurationId, String eventId, Instant raceStart) {
        return new MapSqlParameterSource("scid", sessionConfigurationId).addValue("eventId", eventId)
                .addValue("start", Timestamp.from(raceStart));
    }
}
