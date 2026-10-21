package com.stellartrack.messaging.contract;

import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.Layer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * Real-shape sample payloads, written the way each producer serializes them today:
 * <ul>
 *   <li>Layer 1: stellartrackbackend {@code buildRacePredictionMessage} → amqp-connection-manager {@code json: true}
 *       (plain JSON, field order of the DTO literal, no type header)</li>
 *   <li>Layer 2: {@code StateChangePublisher} HashMap → Spring AMQP default Jackson 2 converter
 *       ({@code Instant} as decimal epoch seconds, {@code LocalTime} as an array, enums as names)</li>
 *   <li>Layer 3: {@code Layer3RankingMessage} → Jackson 2 with JavaTimeModule and ISO dates
 *       ({@code isTop4}/{@code isNap} fields serialised as {@code top4}/{@code nap})</li>
 * </ul>
 */
public final class LayerSamples {

    public static final String CARD = "1096-2025-08-01";
    public static final String L2_TYPE = "java.util.HashMap";
    public static final String L3_TYPE = "com.stellartrack.layer3.event.dto.Layer3RankingMessage";

    private LayerSamples() {
    }

    /** Race 3 prediction: top 4 + other runners (choice continues), bet365 odds with 0 = unknown. */
    public static String layer1(String cardId, int raceNumber, int firstPlanetScore) {
        return """
                {"cardId":"%s","raceNumber":%d,"raceDate":"%s",\
                "finalPrediction":[{"horseNumber":5,"choice":1,"planetScore":%d},{"horseNumber":1,"choice":2,"planetScore":6},\
                {"horseNumber":3,"choice":3,"planetScore":4},{"horseNumber":2,"choice":4,"planetScore":3}],\
                "otherRunners":[{"horseNumber":4,"choice":5,"planetScore":0},{"horseNumber":6,"choice":6,"planetScore":0}],\
                "odds":[{"horseNumber":1,"odds":3.4},{"horseNumber":2,"odds":5.5},{"horseNumber":3,"odds":0},\
                {"horseNumber":4,"odds":12},{"horseNumber":5,"odds":2.63},{"horseNumber":6,"odds":0}]}"""
                .formatted(cardId, raceNumber, cardId.substring(cardId.indexOf('-') + 1), firstPlanetScore);
    }

    /** Layer 2 state change after race {@code raceNumber}, timestamps written as decimal epoch seconds. */
    public static String layer2EpochSeconds(String cardId, int raceNumber, Instant at) {
        return layer2(cardId, raceNumber, epochSeconds(at), "[14,30]", epochSeconds(at.minusSeconds(3600)));
    }

    /** Same message with ISO-8601 timestamps and a textual time, as a differently configured mapper would write. */
    public static String layer2Iso(String cardId, int raceNumber, Instant at) {
        return layer2(cardId, raceNumber, "\"" + at + "\"", "\"14:30:00\"", "\"" + at.minusSeconds(3600) + "\"");
    }

    private static String layer2(String cardId, int raceNumber, String timestamp, String raceTime, String created) {
        StringBuilder states = new StringBuilder();
        String[][] planets = {
                {"MARS", "DOMINANT_CONTINUATION", "Dominant Continuation", "88", "EXPLOSIVE"},
                {"VENUS", "ACTIVE", "Active", "61", "STRONG"},
                {"MERCURY", "COOLING", "Cooling", "15", "NEUTRAL"},
                {"MOON", "ABSENT_PRESSURE", "Absent Pressure", "40", "BUILDING"},
                {"SUN", "NEUTRAL", "Neutral", "20", "NEUTRAL"}};
        for (String[] p : planets) {
            if (!states.isEmpty()) {
                states.append(',');
            }
            states.append("""
                    "%s":{"cardId":"%s","raceNumber":%d,"planet":"%s","stateCode":"%s","stateName":"%s",\
                    "strengthScore":%s,"authorityLevel":"%s","positionTrend":[{"runnerNumbers":[1,8],"positions":[1,4],\
                    "raceNumber":%d,"raceTime":%s,"hora":3,"zone":"FIRE"}],"momentum":"RISING","absenceDuration":0,\
                    "winCount":1,"top4Count":2,"racesSinceLastWin":0,"triggeringRules":["R-12","R-30"],\
                    "contextFlags":{"is_in_planet":true,"out_streak":1},"rulesetVersion":"2.3.0","timestamp":%s}"""
                    .formatted(p[0], cardId, raceNumber, p[0], p[1], p[2], p[3], p[4], raceNumber, raceTime, timestamp));
        }
        return """
                {"raceCardContext":{"cardId":"%s","inPlanets":["MARS","VENUS","SUN"],"outPlanets":["MERCURY","MOON"],\
                "scenario":"EQUA3","currentOutStreak":1,"lastRaceProcessed":%d,"lastRaceWinner":5,"createdAt":%s,\
                "lastModified":%s,"totalRaces":8},"cardId":"%s","states":{%s},"raceNumber":%d,"timestamp":%s}"""
                .formatted(cardId, raceNumber, created, timestamp, cardId, states, raceNumber, timestamp);
    }

    /** Layer 3 ranking; {@code javaFieldNames} uses {@code isTop4}/{@code isNap} instead of {@code top4}/{@code nap}. */
    public static String layer3(String cardId, int raceNumber, String source, Instant generatedAt, boolean javaFieldNames) {
        String top4 = javaFieldNames ? "isTop4" : "top4";
        String nap = javaFieldNames ? "isNap" : "nap";
        return """
                {"cardId":"%s","raceNumber":%d,"source":"%s","rankings":[\
                {"horseId":"MARS","horseNumber":1,"finalRank":1,"selectionConfidenceScore":77.0,"confidenceScore":82.1,"rationaleTags":["DOMINANT_CONTINUATION","IN_PLANET"],"%s":true,"%s":true,"odds":3.4},\
                {"horseId":"SUN","horseNumber":5,"finalRank":2,"selectionConfidenceScore":70.5,"confidenceScore":74.0,"rationaleTags":["L1_TOP_CHOICE"],"%s":true,"%s":false,"odds":2.63},\
                {"horseId":"MERCURY","horseNumber":3,"finalRank":3,"selectionConfidenceScore":51.0,"confidenceScore":55.2,"rationaleTags":[],"%s":true,"%s":false,"odds":null},\
                {"horseId":"VENUS","horseNumber":2,"finalRank":4,"selectionConfidenceScore":44.0,"confidenceScore":48.0,"rationaleTags":[],"%s":true,"%s":false,"odds":5.5},\
                {"horseId":"MOON","horseNumber":4,"finalRank":5,"selectionConfidenceScore":20.0,"confidenceScore":25.0,"rationaleTags":[],"%s":false,"%s":false,"odds":12.0}],\
                "rerankPolicyVersion":"v1.0","generatedAt":"%s"}"""
                .formatted(cardId, raceNumber, source, top4, nap, top4, nap, top4, nap, top4, nap, top4, nap, generatedAt);
    }

    /** Jackson 2 {@code WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS}: seconds with a nine-digit fraction. */
    public static String epochSeconds(Instant at) {
        return at.getEpochSecond() + "." + String.format("%09d", at.getNano());
    }

    public static InboundMessage message(Layer layer, String body, String routingKey, Map<String, String> headers) {
        return new InboundMessage(layer, body.getBytes(StandardCharsets.UTF_8), "application/json", null,
                "stellartrack.phase2." + layer.token(), "stellar-track-exchange", routingKey, false, 1, headers,
                Instant.now(), null);
    }
}
