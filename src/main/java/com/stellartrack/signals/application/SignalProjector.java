package com.stellartrack.signals.application;

import com.stellartrack.common.events.UiNotification;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.messaging.contract.layer1.Layer1PredictionContract;
import com.stellartrack.messaging.contract.layer2.Layer2StateChangeContract;
import com.stellartrack.messaging.contract.layer3.Layer3RankingContract;
import com.stellartrack.messaging.domain.LayerRecordAcceptedEvent;
import com.stellartrack.messaging.persistence.LayerSourceRecordEntity;
import com.stellartrack.messaging.persistence.LayerSourceRecordRepository;
import com.stellartrack.messaging.persistence.MessageReceiptEntity;
import com.stellartrack.messaging.persistence.MessageReceiptRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Projects each accepted layer record into the typed race-signal tables, keeping the latest revision per race
 * (Layer 2/3 by source timestamp, Layer 1 by arrival since it has none), then announces the change to the UI.
 * The record is already committed and acknowledged when this runs, so a projection failure is logged and never
 * changes the receipt outcome.
 */
@Component
public class SignalProjector {

    private static final Logger log = LoggerFactory.getLogger(SignalProjector.class);

    private static final Set<String> PROJECTED_VERSIONS = Set.of(Layer1PredictionContract.VERSION,
            Layer2StateChangeContract.VERSION, Layer3RankingContract.VERSION);

    private final LayerSourceRecordRepository records;
    private final MessageReceiptRepository receipts;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate tx;

    public SignalProjector(LayerSourceRecordRepository records, MessageReceiptRepository receipts,
            NamedParameterJdbcTemplate jdbc, JsonCodec json, ApplicationEventPublisher events, Clock clock,
            PlatformTransactionManager txManager) {
        this.records = records;
        this.receipts = receipts;
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener
    public void onAccepted(LayerRecordAcceptedEvent event) {
        try {
            project(event);
        } catch (RuntimeException e) {
            log.error("Race-signal projection failed for {} record {}", event.layer(), event.recordId(), e);
        }
    }

    void project(LayerRecordAcceptedEvent event) {
        Optional<LayerSourceRecordEntity> found = records.findById(event.recordId());
        if (found.isEmpty()) {
            return;
        }
        LayerSourceRecordEntity record = found.get();
        if (!PROJECTED_VERSIONS.contains(record.getContractVersion())) {
            return;
        }
        Instant receivedAt = receipts.findById(event.receiptId()).map(MessageReceiptEntity::getReceivedAt)
                .orElse(record.getCreatedAt());
        JsonNode payload = json.readTree(record.getNormalizedPayload());
        String cardId = payload.path("cardId").asString();
        int raceNumber = payload.path("raceNumber").intValue();
        MapSqlParameterSource base = new MapSqlParameterSource()
                .addValue("cardId", cardId)
                .addValue("raceNumber", raceNumber)
                .addValue("receivedAt", Timestamp.from(receivedAt))
                .addValue("receiptId", event.receiptId())
                .addValue("recordId", record.getId())
                .addValue("now", Timestamp.from(clock.instant()));
        switch (record.getContractVersion()) {
            case Layer1PredictionContract.VERSION -> tx.executeWithoutResult(s -> {
                upsertCard(payload, base);
                projectLayer1(payload, base);
            });
            case Layer2StateChangeContract.VERSION -> tx.executeWithoutResult(s -> {
                upsertCard(payload, base);
                projectLayer2(payload, base);
            });
            case Layer3RankingContract.VERSION -> tx.executeWithoutResult(s -> {
                upsertCard(payload, base);
                projectLayer3(payload, base);
            });
            default -> throw new IllegalStateException("Unprojected contract " + record.getContractVersion());
        }
        events.publishEvent(UiNotification.of("signals", cardId,
                Map.of("layer", event.layer().name(), "raceNumber", raceNumber)));
    }

    private void upsertCard(JsonNode payload, MapSqlParameterSource base) {
        MapSqlParameterSource params = new MapSqlParameterSource(base.getValues())
                .addValue("venueId", payload.path("venueId").asString())
                .addValue("raceDate", Date.valueOf(LocalDate.parse(payload.path("raceDate").asString())));
        jdbc.update("""
                insert into race_signal_card (card_id, venue_id, race_date, updated_at)
                values (:cardId, :venueId, :raceDate, :now)
                on conflict (card_id) do update set updated_at = greatest(race_signal_card.updated_at, excluded.updated_at)
                """, params);
    }

    private void projectLayer1(JsonNode payload, MapSqlParameterSource base) {
        jdbc.update("""
                insert into race_signal_l1_prediction (card_id, race_number, revision, runners, received_at, receipt_id, record_id)
                values (:cardId, :raceNumber, 1, cast(:runners as jsonb), :receivedAt, :receiptId, :recordId)
                on conflict (card_id, race_number) do update set
                    revision = race_signal_l1_prediction.revision + 1, runners = excluded.runners,
                    received_at = excluded.received_at, receipt_id = excluded.receipt_id, record_id = excluded.record_id
                """, new MapSqlParameterSource(base.getValues()).addValue("runners", payload.path("runners").toString()));
    }

    private void projectLayer2(JsonNode payload, MapSqlParameterSource base) {
        Timestamp sourceEventAt = Timestamp.from(Instant.parse(payload.path("timestamp").asString()));
        for (Map.Entry<String, JsonNode> entry : payload.path("states").properties()) {
            JsonNode s = entry.getValue();
            MapSqlParameterSource params = new MapSqlParameterSource(base.getValues())
                    .addValue("planet", entry.getKey())
                    .addValue("stateCode", text(s, "stateCode"))
                    .addValue("stateName", text(s, "stateName"))
                    .addValue("strength", integer(s, "strengthScore"))
                    .addValue("authority", text(s, "authorityLevel"))
                    .addValue("momentum", text(s, "momentum"))
                    .addValue("winCount", integer(s, "winCount"))
                    .addValue("top4Count", integer(s, "top4Count"))
                    .addValue("absence", integer(s, "absenceDuration"))
                    .addValue("sinceWin", integer(s, "racesSinceLastWin"))
                    .addValue("rules", jsonOr(s, "triggeringRules", "[]"))
                    .addValue("flags", jsonOr(s, "contextFlags", "{}"))
                    .addValue("trend", jsonOr(s, "positionTrend", "[]"))
                    .addValue("ruleset", text(s, "rulesetVersion"))
                    .addValue("sourceEventAt", sourceEventAt);
            jdbc.update("""
                    insert into race_signal_planetary_state (card_id, race_number, planet, state_code, state_name,
                        strength_score, authority_level, momentum, win_count, top4_count, absence_duration,
                        races_since_last_win, triggering_rules, context_flags, position_trend, ruleset_version,
                        source_event_at, received_at, receipt_id, record_id)
                    values (:cardId, :raceNumber, :planet, :stateCode, :stateName, :strength, :authority, :momentum,
                        :winCount, :top4Count, :absence, :sinceWin, cast(:rules as jsonb), cast(:flags as jsonb),
                        cast(:trend as jsonb), :ruleset, :sourceEventAt, :receivedAt, :receiptId, :recordId)
                    on conflict (card_id, race_number, planet) do update set
                        state_code = excluded.state_code, state_name = excluded.state_name,
                        strength_score = excluded.strength_score, authority_level = excluded.authority_level,
                        momentum = excluded.momentum, win_count = excluded.win_count, top4_count = excluded.top4_count,
                        absence_duration = excluded.absence_duration, races_since_last_win = excluded.races_since_last_win,
                        triggering_rules = excluded.triggering_rules, context_flags = excluded.context_flags,
                        position_trend = excluded.position_trend, ruleset_version = excluded.ruleset_version,
                        source_event_at = excluded.source_event_at, received_at = excluded.received_at,
                        receipt_id = excluded.receipt_id, record_id = excluded.record_id
                    where race_signal_planetary_state.source_event_at <= excluded.source_event_at
                    """, params);
        }
        JsonNode context = payload.get("raceCardContext");
        if (context != null && context.isObject()) {
            jdbc.update("""
                    update race_signal_card set
                        total_races = coalesce(:totalRaces, total_races),
                        in_planets = coalesce(cast(:inPlanets as jsonb), in_planets),
                        out_planets = coalesce(cast(:outPlanets as jsonb), out_planets),
                        scenario = coalesce(:scenario, scenario),
                        current_out_streak = coalesce(:outStreak, current_out_streak),
                        last_race_processed = coalesce(:lastRace, last_race_processed),
                        context_updated_at = :sourceEventAt
                    where card_id = :cardId and (context_updated_at is null or context_updated_at <= :sourceEventAt)
                    """, new MapSqlParameterSource(base.getValues())
                    .addValue("totalRaces", integer(context, "totalRaces"))
                    .addValue("inPlanets", context.has("inPlanets") ? context.get("inPlanets").toString() : null)
                    .addValue("outPlanets", context.has("outPlanets") ? context.get("outPlanets").toString() : null)
                    .addValue("scenario", text(context, "scenario"))
                    .addValue("outStreak", integer(context, "currentOutStreak"))
                    .addValue("lastRace", integer(context, "lastRaceProcessed"))
                    .addValue("sourceEventAt", sourceEventAt));
        }
    }

    private void projectLayer3(JsonNode payload, MapSqlParameterSource base) {
        jdbc.update("""
                insert into race_signal_l3_ranking (card_id, race_number, source, rerank_policy_version, generated_at,
                    rankings, nap_horse_number, received_at, receipt_id, record_id)
                values (:cardId, :raceNumber, :source, :policy, :generatedAt, cast(:rankings as jsonb), :nap,
                    :receivedAt, :receiptId, :recordId)
                on conflict (card_id, race_number, source) do update set
                    rerank_policy_version = excluded.rerank_policy_version, generated_at = excluded.generated_at,
                    rankings = excluded.rankings, nap_horse_number = excluded.nap_horse_number,
                    received_at = excluded.received_at, receipt_id = excluded.receipt_id, record_id = excluded.record_id
                where race_signal_l3_ranking.generated_at <= excluded.generated_at
                """, new MapSqlParameterSource(base.getValues())
                .addValue("source", text(payload, "source"))
                .addValue("policy", text(payload, "rerankPolicyVersion"))
                .addValue("generatedAt", Timestamp.from(Instant.parse(payload.path("generatedAt").asString())))
                .addValue("rankings", payload.path("rankings").toString())
                .addValue("nap", integer(payload, "napHorseNumber")));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.intValue();
    }

    private static String jsonOr(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.toString();
    }
}
