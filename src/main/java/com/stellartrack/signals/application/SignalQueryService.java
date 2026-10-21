package com.stellartrack.signals.application;

import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.messaging.correlation.BetfairMappingProperties;
import com.stellartrack.messaging.correlation.RaceMarketCorrelator;
import com.stellartrack.messaging.domain.Planet;
import com.stellartrack.signals.application.SignalViews.CardDetail;
import com.stellartrack.signals.application.SignalViews.CardSummary;
import com.stellartrack.signals.application.SignalViews.Layer1Runner;
import com.stellartrack.signals.application.SignalViews.Layer1Section;
import com.stellartrack.signals.application.SignalViews.Layer2Section;
import com.stellartrack.signals.application.SignalViews.Layer3Section;
import com.stellartrack.signals.application.SignalViews.LayerState;
import com.stellartrack.signals.application.SignalViews.Mapping;
import com.stellartrack.signals.application.SignalViews.MergedRunner;
import com.stellartrack.signals.application.SignalViews.PlanetState;
import com.stellartrack.signals.application.SignalViews.RaceDetail;
import com.stellartrack.signals.application.SignalViews.RaceSummary;
import com.stellartrack.signals.application.SignalViews.Ranking;
import com.stellartrack.signals.application.SignalViews.Trace;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Card list, per-card race timeline and the combined Layer 1/2/3 view of one race. */
@Service
@Transactional(readOnly = true)
public class SignalQueryService {

    /** Layer 3's Layer 1-driven ranking is preferred over its standalone ranking when both exist. */
    private static final List<String> SOURCE_ORDER = List.of("LAYER1_RERANKED", "STANDALONE");

    private static final String CARD_SELECT = """
            select c.card_id, c.venue_id, c.race_date, c.total_races, c.in_planets::text as in_planets,
                c.out_planets::text as out_planets, c.scenario, c.current_out_streak, c.last_race_processed, c.updated_at,
                (select count(*) from race_signal_l1_prediction p where p.card_id = c.card_id) as l1_races,
                (select count(distinct s.race_number) from race_signal_planetary_state s where s.card_id = c.card_id) as l2_races,
                (select count(distinct r.race_number) from race_signal_l3_ranking r where r.card_id = c.card_id) as l3_races,
                greatest(
                    coalesce((select max(p.race_number) from race_signal_l1_prediction p where p.card_id = c.card_id), 0),
                    coalesce((select max(r.race_number) from race_signal_l3_ranking r where r.card_id = c.card_id), 0),
                    coalesce((select max(s.race_number) + 1 from race_signal_planetary_state s where s.card_id = c.card_id), 0)
                ) as max_race
            from race_signal_card c
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;
    private final BetfairMappingProperties mapping;
    private final RaceMarketCorrelator correlator;

    public SignalQueryService(NamedParameterJdbcTemplate jdbc, JsonCodec json, BetfairMappingProperties mapping,
            RaceMarketCorrelator correlator) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapping = mapping;
        this.correlator = correlator;
    }

    public List<CardSummary> cards(LocalDate date, String venueId) {
        StringBuilder sql = new StringBuilder(CARD_SELECT).append(" where 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (date != null) {
            sql.append(" and c.race_date = :date");
            params.addValue("date", Date.valueOf(date));
        }
        if (venueId != null && !venueId.isBlank()) {
            sql.append(" and c.venue_id = :venueId");
            params.addValue("venueId", venueId.trim());
        }
        sql.append(" order by c.race_date desc, c.venue_id limit 200");
        return jdbc.query(sql.toString(), params, (rs, i) -> card(rs));
    }

    public CardDetail card(String cardId) {
        CardSummary card = findCard(cardId);
        Map<Integer, Integer> l1 = new HashMap<>();
        jdbc.query("select race_number, revision from race_signal_l1_prediction where card_id = :cardId",
                Map.of("cardId", cardId), rs -> {
                    l1.put(rs.getInt("race_number"), rs.getInt("revision"));
                });
        Set<Integer> l2 = new LinkedHashSet<>(jdbc.queryForList(
                "select distinct race_number from race_signal_planetary_state where card_id = :cardId",
                Map.of("cardId", cardId), Integer.class));
        Map<Integer, List<String>> l3 = new HashMap<>();
        Map<Integer, Integer> naps = new HashMap<>();
        jdbc.query("select race_number, source, nap_horse_number from race_signal_l3_ranking where card_id = :cardId",
                Map.of("cardId", cardId), rs -> {
                    int race = rs.getInt("race_number");
                    String source = rs.getString("source");
                    l3.computeIfAbsent(race, k -> new ArrayList<>()).add(source);
                    Integer nap = (Integer) rs.getObject("nap_horse_number");
                    if (nap != null && (!naps.containsKey(race) || SOURCE_ORDER.indexOf(source) == 0)) {
                        naps.put(race, nap);
                    }
                });
        int races = Math.max(card.totalRaces() == null ? 0 : card.totalRaces(), card.racesSeen());
        List<RaceSummary> summaries = new ArrayList<>();
        for (int race = 1; race <= races; race++) {
            List<String> sources = l3.getOrDefault(race, List.of()).stream()
                    .sorted(Comparator.comparingInt(SOURCE_ORDER::indexOf)).toList();
            summaries.add(new RaceSummary(race,
                    state(race, l1.containsKey(race)),
                    state(race, l2.contains(race - 1)),
                    state(race, !sources.isEmpty()),
                    l1.get(race), sources, naps.get(race)));
        }
        return new CardDetail(card, summaries);
    }

    public RaceDetail race(String cardId, int raceNumber) {
        CardSummary card = findCard(cardId);
        Layer1Section layer1 = layer1(cardId, raceNumber);
        Layer2Section layer2 = layer2(cardId, raceNumber);
        List<RankingRow> rankingRows = rankingRows(cardId, raceNumber);

        Set<Integer> horses = new LinkedHashSet<>();
        layer1.runners().forEach(r -> horses.add(r.horseNumber()));
        rankingRows.forEach(row -> row.runners().forEach(n -> horses.add(n.path("horseNumber").intValue())));
        RaceMarketCorrelator.Resolution resolution = correlator.resolve(cardId, raceNumber, horses);

        List<Ranking> rankings = rankingRows.stream()
                .map(row -> new Ranking(row.source(), row.policy(), row.generatedAt(), row.nap(), row.trace(),
                        merge(layer1.runners(), row.runners(), resolution)))
                .toList();
        Layer3Section layer3 = new Layer3Section(state(raceNumber, !rankings.isEmpty()), rankings);

        Map<String, Trace> trace = new LinkedHashMap<>();
        if (layer1.trace() != null) {
            trace.put("LAYER1", layer1.trace());
        }
        if (layer2.trace() != null) {
            trace.put("LAYER2", layer2.trace());
        }
        rankings.forEach(r -> trace.put("LAYER3_" + r.source(), r.trace()));
        return new RaceDetail(card.cardId(), card.venueId(), card.betfairVenue(), card.raceDate(), raceNumber,
                card.totalRaces(), card.inPlanets(), card.outPlanets(), card.scenario(), layer1, layer2, layer3,
                new Mapping(resolution.status().name(), resolution.reason(), resolution.betfairVenue(),
                        resolution.marketId()),
                trace);
    }

    private Layer1Section layer1(String cardId, int raceNumber) {
        List<Layer1Section> found = jdbc.query("""
                select revision, runners::text as runners, received_at, receipt_id, record_id
                from race_signal_l1_prediction where card_id = :cardId and race_number = :race
                """, Map.of("cardId", cardId, "race", raceNumber), (rs, i) -> {
            List<Layer1Runner> runners = new ArrayList<>();
            for (JsonNode n : json.readTree(rs.getString("runners"))) {
                runners.add(new Layer1Runner(n.path("horseNumber").intValue(), n.path("choice").intValue(),
                        n.hasNonNull("planetScore") ? n.get("planetScore").intValue() : null,
                        decimal(n, "odds"), text(n, "planet"), n.path("isFinal").asBoolean(false)));
            }
            return new Layer1Section(LayerState.RECEIVED, rs.getInt("revision"),
                    new Trace(uuid(rs, "receipt_id"), uuid(rs, "record_id"), instant(rs, "received_at"), null), runners);
        });
        return found.isEmpty() ? new Layer1Section(state(raceNumber, false), null, null, List.of()) : found.getFirst();
    }

    private Layer2Section layer2(String cardId, int raceNumber) {
        if (raceNumber <= 1) {
            return new Layer2Section(LayerState.NOT_EXPECTED, null, null, List.of());
        }
        int input = raceNumber - 1;
        List<Trace> traces = new ArrayList<>();
        List<PlanetState> states = new ArrayList<>(jdbc.query("""
                select planet, state_code, state_name, strength_score, authority_level, momentum, win_count, top4_count,
                    absence_duration, races_since_last_win, triggering_rules::text as triggering_rules,
                    context_flags::text as context_flags, position_trend::text as position_trend, ruleset_version,
                    source_event_at, received_at, receipt_id, record_id
                from race_signal_planetary_state where card_id = :cardId and race_number = :race
                order by received_at desc
                """, Map.of("cardId", cardId, "race", input), (rs, i) -> {
            traces.add(new Trace(uuid(rs, "receipt_id"), uuid(rs, "record_id"), instant(rs, "received_at"),
                    instant(rs, "source_event_at")));
            return new PlanetState(rs.getString("planet"), rs.getString("state_code"), rs.getString("state_name"),
                    integer(rs, "strength_score"), rs.getString("authority_level"), rs.getString("momentum"),
                    integer(rs, "win_count"), integer(rs, "top4_count"), integer(rs, "absence_duration"),
                    integer(rs, "races_since_last_win"), plain(rs.getString("triggering_rules")),
                    plain(rs.getString("context_flags")), plain(rs.getString("position_trend")),
                    rs.getString("ruleset_version"));
        }));
        List<String> order = Arrays.stream(Planet.values()).map(Enum::name).toList();
        states.sort(Comparator.comparingInt(s -> order.indexOf(s.planet())));
        return new Layer2Section(states.isEmpty() ? LayerState.WAITING : LayerState.RECEIVED, input,
                traces.isEmpty() ? null : traces.getFirst(), states);
    }

    private record RankingRow(String source, String policy, Instant generatedAt, Integer nap, Trace trace,
            List<JsonNode> runners) {
    }

    private List<RankingRow> rankingRows(String cardId, int raceNumber) {
        List<RankingRow> rows = new ArrayList<>(jdbc.query("""
                select source, rerank_policy_version, generated_at, rankings::text as rankings, nap_horse_number,
                    received_at, receipt_id, record_id
                from race_signal_l3_ranking where card_id = :cardId and race_number = :race
                """, Map.of("cardId", cardId, "race", raceNumber), (rs, i) -> {
            List<JsonNode> runners = new ArrayList<>();
            json.readTree(rs.getString("rankings")).forEach(runners::add);
            return new RankingRow(rs.getString("source"), rs.getString("rerank_policy_version"),
                    instant(rs, "generated_at"), integer(rs, "nap_horse_number"),
                    new Trace(uuid(rs, "receipt_id"), uuid(rs, "record_id"), instant(rs, "received_at"),
                            instant(rs, "generated_at")),
                    runners);
        }));
        rows.sort(Comparator.comparingInt(r -> SOURCE_ORDER.indexOf(r.source())));
        return rows;
    }

    /** Layer 3 ranked runners first (by final rank), then runners only Layer 1 listed (by choice). */
    static List<MergedRunner> merge(List<Layer1Runner> layer1, List<JsonNode> layer3,
            RaceMarketCorrelator.Resolution resolution) {
        Map<Integer, Layer1Runner> byHorse = new HashMap<>();
        layer1.forEach(r -> byHorse.putIfAbsent(r.horseNumber(), r));
        List<MergedRunner> merged = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (JsonNode n : layer3) {
            int horse = n.path("horseNumber").intValue();
            seen.add(horse);
            Layer1Runner l1 = byHorse.get(horse);
            int rank = n.path("finalRank").intValue();
            List<String> tags = new ArrayList<>();
            n.path("rationaleTags").forEach(tag -> tags.add(tag.asString()));
            BigDecimal odds = decimal(n, "odds");
            merged.add(new MergedRunner(horse, planet(horse), l1 == null ? null : l1.choice(),
                    l1 == null ? null : l1.isFinal(), rank, l1 == null ? null : l1.choice() - rank,
                    decimal(n, "confidenceScore"), decimal(n, "selectionConfidenceScore"),
                    n.path("nap").asBoolean(false), n.path("top4").asBoolean(false),
                    odds != null ? odds : l1 == null ? null : l1.odds(), tags,
                    resolution.selectionByHorse().get(horse), mappingStatus(resolution, horse)));
        }
        layer1.stream().filter(r -> !seen.contains(r.horseNumber()))
                .sorted(Comparator.comparingInt(Layer1Runner::choice))
                .forEach(r -> merged.add(new MergedRunner(r.horseNumber(), planet(r.horseNumber()), r.choice(),
                        r.isFinal(), null, null, null, null, false, false, r.odds(), List.of(),
                        resolution.selectionByHorse().get(r.horseNumber()), mappingStatus(resolution, r.horseNumber()))));
        return merged;
    }

    private static String mappingStatus(RaceMarketCorrelator.Resolution resolution, int horse) {
        if (resolution.selectionByHorse().containsKey(horse)) {
            return "MAPPED";
        }
        return resolution.marketId() == null ? resolution.status().name() : "RUNNER_NOT_FOUND";
    }

    private static String planet(int horse) {
        return Planet.ofRunner(horse).map(Enum::name).orElse(null);
    }

    private static LayerState state(int raceNumber, boolean received) {
        if (raceNumber <= 1) {
            return received ? LayerState.RECEIVED : LayerState.NOT_EXPECTED;
        }
        return received ? LayerState.RECEIVED : LayerState.WAITING;
    }

    private CardSummary findCard(String cardId) {
        List<CardSummary> found = jdbc.query(CARD_SELECT + " where c.card_id = :cardId", Map.of("cardId", cardId),
                (rs, i) -> card(rs));
        if (found.isEmpty()) {
            throw new NotFoundException("Race card", cardId);
        }
        return found.getFirst();
    }

    private CardSummary card(ResultSet rs) throws SQLException {
        String venueId = rs.getString("venue_id");
        BetfairMappingProperties.VenueMapping venue = mapping.venue(venueId);
        return new CardSummary(rs.getString("card_id"), venueId, venue == null ? null : venue.betfairVenue(),
                rs.getDate("race_date").toLocalDate(), integer(rs, "total_races"), rs.getInt("max_race"),
                rs.getLong("l1_races"), rs.getLong("l2_races"), rs.getLong("l3_races"),
                strings(rs.getString("in_planets")), strings(rs.getString("out_planets")), rs.getString("scenario"),
                integer(rs, "current_out_streak"), integer(rs, "last_race_processed"), instant(rs, "updated_at"));
    }

    private List<String> strings(String jsonArray) {
        if (jsonArray == null) {
            return null;
        }
        List<String> values = new ArrayList<>();
        json.readTree(jsonArray).forEach(n -> values.add(n.asString()));
        return values;
    }

    private Object plain(String jsonText) {
        return jsonText == null ? null : json.read(jsonText, Object.class);
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.decimalValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
