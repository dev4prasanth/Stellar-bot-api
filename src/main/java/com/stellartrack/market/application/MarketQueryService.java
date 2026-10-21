package com.stellartrack.market.application;

import com.stellartrack.common.api.Freshness;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSearch;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.market.domain.RacingEvent;
import com.stellartrack.market.filter.FilterOutcome;
import com.stellartrack.market.filter.RunnerFilterCriteria;
import com.stellartrack.market.filter.RunnerFilterEvaluator;
import com.stellartrack.market.port.MarketDataPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Read side for market browsing (Manual Mode) and filter explanations. */
@Service
public class MarketQueryService {

    public record MarketListQuery(Instant from, Instant to, Set<String> countries, Set<String> venues,
            Set<MarketType> marketTypes, String status, Boolean inPlay, String recommendationStatus) {
    }

    public record MarketListItem(String marketId, String eventName, String venue, String countryCode, String marketName,
            MarketType marketType, Instant startTime, String status, boolean inPlay, String raceType, int runnerCount,
            Instant catalogueSyncedAt, Instant statusSyncedAt, long pendingRecommendations) {
    }

    public record RunnerView(long selectionId, String name, Integer sortPriority, Map<String, String> metadata,
            MarketPrices.RunnerPrices prices, Integer priceRank, FilterOutcome filter) {
    }

    public record MarketDetail(MarketSummary market, MarketPrices prices, Freshness freshness, BetSide side,
            List<RunnerView> runners) {
    }

    public record FilterOptions(List<String> countries, List<String> venues, List<String> raceTypes) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final MarketCatalogService catalog;
    private final MarketDataPort marketData;
    private final RunnerFilterEvaluator filterEvaluator;
    private final Duration marketBookMaxAge;
    private final Clock clock;

    public MarketQueryService(NamedParameterJdbcTemplate jdbc, MarketCatalogService catalog, MarketDataPort marketData,
            RunnerFilterEvaluator filterEvaluator,
            @Value("${stellartrack.betfair.staleness.market-book-max-age}") Duration marketBookMaxAge, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.marketData = marketData;
        this.filterEvaluator = filterEvaluator;
        this.marketBookMaxAge = marketBookMaxAge;
        this.clock = clock;
    }

    public List<MarketListItem> list(MarketListQuery query) {
        StringBuilder sql = new StringBuilder("""
                select m.*, (select count(*) from market_runner r where r.market_id = m.market_id) as runner_count,
                       (select count(*) from recommendation rec where rec.market_id = m.market_id
                          and rec.status = 'PENDING_APPROVAL') as pending_count
                from market m where m.market_start_time >= :from and m.market_start_time < :to""");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(query.from()))
                .addValue("to", Timestamp.from(query.to()));
        if (query.countries() != null && !query.countries().isEmpty()) {
            sql.append(" and m.country_code in (:countries)");
            params.addValue("countries", query.countries());
        }
        if (query.venues() != null && !query.venues().isEmpty()) {
            sql.append(" and m.venue in (:venues)");
            params.addValue("venues", query.venues());
        }
        if (query.marketTypes() != null && !query.marketTypes().isEmpty()) {
            sql.append(" and m.market_type in (:types)");
            params.addValue("types", query.marketTypes().stream().map(Enum::name).toList());
        }
        if (query.status() != null) {
            sql.append(" and m.status = :status");
            params.addValue("status", query.status());
        }
        if (query.inPlay() != null) {
            sql.append(" and m.in_play = :inPlay");
            params.addValue("inPlay", query.inPlay());
        }
        if (query.recommendationStatus() != null) {
            sql.append(" and exists (select 1 from recommendation rec where rec.market_id = m.market_id and rec.status = :recStatus)");
            params.addValue("recStatus", query.recommendationStatus());
        }
        sql.append(" order by m.market_start_time, m.venue limit 500");
        return jdbc.query(sql.toString(), params, (rs, i) -> toListItem(rs));
    }

    public MarketDetail detail(String marketId, BetSide side, RunnerFilterCriteria criteria) {
        MarketSummary market = catalog.require(marketId);
        MarketPrices prices = marketData.getPrices(List.of(marketId)).stream().findFirst().orElse(null);
        Freshness freshness = prices == null
                ? Freshness.unavailable("Betfair", "No market book returned")
                : Freshness.evaluate(prices.asOf(), marketBookMaxAge, prices.dataDelayed(), prices.source().name(), clock);
        BetSide effectiveSide = side == null ? BetSide.BACK : side;
        Map<Long, FilterOutcome> outcomes = filterEvaluator.evaluate(market, prices, criteria, effectiveSide).stream()
                .collect(Collectors.toMap(FilterOutcome::selectionId, Function.identity()));
        Map<Long, Integer> ranks = filterEvaluator.priceRanks(prices);
        List<RunnerView> runners = market.runners().stream()
                .map(r -> new RunnerView(r.selectionId(), r.name(), r.sortPriority(), r.metadata(),
                        prices == null ? null : prices.runner(r.selectionId()).orElse(null),
                        ranks.get(r.selectionId()), outcomes.get(r.selectionId())))
                .toList();
        return new MarketDetail(market, prices, freshness, effectiveSide, runners);
    }

    public FilterOptions filterOptions(Instant from, Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        return new FilterOptions(
                distinct("country_code", params),
                distinct("venue", params),
                distinct("race_type", params));
    }

    /** Live provider event list (listEvents) for the period, e.g. to show meetings not yet cached. */
    public List<RacingEvent> events(Instant from, Instant to) {
        return marketData.listEvents(MarketSearch.upcoming(from, to));
    }

    private List<String> distinct(String column, MapSqlParameterSource params) {
        // column names are constants from this class, never user input
        return jdbc.queryForList("select distinct " + column + " from market where " + column
                + " is not null and market_start_time >= :from and market_start_time < :to order by 1", params, String.class);
    }

    private static MarketListItem toListItem(ResultSet rs) throws SQLException {
        return new MarketListItem(
                rs.getString("market_id"), rs.getString("event_name"), rs.getString("venue"),
                rs.getString("country_code"), rs.getString("market_name"), MarketType.valueOf(rs.getString("market_type")),
                rs.getTimestamp("market_start_time").toInstant(), rs.getString("status"), rs.getBoolean("in_play"),
                rs.getString("race_type"), rs.getInt("runner_count"), instant(rs, "catalogue_synced_at"),
                instant(rs, "status_synced_at"), rs.getLong("pending_count"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
