package com.stellartrack.messaging.contract.layer2;

import static com.stellartrack.messaging.contract.PayloadFields.putIfPresent;

import com.stellartrack.common.util.Hashing;
import com.stellartrack.messaging.contract.LayerMessageMapper;
import com.stellartrack.messaging.contract.PayloadFields;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.NormalizedLayerRecord;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes a Layer 2 state change; timestamps become ISO-8601 text and times of day {@code HH:mm[:ss]}.
 * Planetary states describe planets, not runners, so no Betfair market links are produced.
 */
public class Layer2StateChangeMapper implements LayerMessageMapper<Layer2StateChange> {

    @Override
    public NormalizedLayerRecord map(Layer2StateChange p, InboundMessage message) {
        String[] card = PayloadFields.splitCardId(p.cardId()).orElseThrow();
        String sha = Hashing.sha256Hex(message.body());
        Map<String, Object> states = new LinkedHashMap<>();
        p.states().forEach((planet, state) -> states.put(planet, state(planet, state)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardId", p.cardId());
        payload.put("venueId", card[0]);
        payload.put("raceDate", card[1]);
        payload.put("raceNumber", p.raceNumber());
        payload.put("timestamp", p.timestamp().toString());
        payload.put("states", states);
        putIfPresent(payload, "raceCardContext", p.raceCardContext() == null ? null : context(p.raceCardContext()));
        payload.put("payloadSha256", sha);
        return new NormalizedLayerRecord("L2:" + p.cardId() + ":" + p.raceNumber() + ":" + sha, null, p.timestamp(),
                Layer2StateChangeContract.VERSION, payload, List.of());
    }

    private static Map<String, Object> state(String planet, Layer2StateChange.PlanetaryState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planet", planet);
        putIfPresent(m, "raceNumber", s.raceNumber());
        putIfPresent(m, "stateCode", s.stateCode());
        putIfPresent(m, "stateName", s.stateName());
        putIfPresent(m, "strengthScore", s.strengthScore());
        putIfPresent(m, "authorityLevel", s.authorityLevel());
        putIfPresent(m, "momentum", s.momentum());
        putIfPresent(m, "absenceDuration", s.absenceDuration());
        putIfPresent(m, "winCount", s.winCount());
        putIfPresent(m, "top4Count", s.top4Count());
        putIfPresent(m, "racesSinceLastWin", s.racesSinceLastWin());
        m.put("triggeringRules", s.triggeringRules());
        m.put("contextFlags", s.contextFlags());
        m.put("positionTrend", s.positionTrend().stream().map(Layer2StateChangeMapper::trend).toList());
        putIfPresent(m, "rulesetVersion", s.rulesetVersion());
        putIfPresent(m, "timestamp", iso(s.timestamp()));
        return m;
    }

    private static Map<String, Object> trend(Layer2StateChange.TrendEntry t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runnerNumbers", t.runnerNumbers());
        m.put("positions", t.positions());
        putIfPresent(m, "raceNumber", t.raceNumber());
        putIfPresent(m, "raceTime", t.raceTime() == null ? null : t.raceTime().toString());
        putIfPresent(m, "hora", t.hora());
        putIfPresent(m, "zone", t.zone());
        return m;
    }

    private static Map<String, Object> context(Layer2StateChange.CardContext c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inPlanets", c.inPlanets());
        m.put("outPlanets", c.outPlanets());
        putIfPresent(m, "scenario", c.scenario());
        putIfPresent(m, "currentOutStreak", c.currentOutStreak());
        putIfPresent(m, "lastRaceProcessed", c.lastRaceProcessed());
        putIfPresent(m, "lastRaceWinner", c.lastRaceWinner());
        putIfPresent(m, "totalRaces", c.totalRaces());
        putIfPresent(m, "createdAt", iso(c.createdAt()));
        putIfPresent(m, "lastModified", iso(c.lastModified()));
        return m;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
