package com.stellartrack.messaging.contract;

import static com.stellartrack.messaging.contract.LayerSamples.CARD;
import static com.stellartrack.messaging.contract.LayerSamples.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.messaging.contract.layer1.Layer1Prediction;
import com.stellartrack.messaging.contract.layer1.Layer1PredictionContract;
import com.stellartrack.messaging.contract.layer2.Layer2StateChange;
import com.stellartrack.messaging.contract.layer2.Layer2StateChangeContract;
import com.stellartrack.messaging.contract.layer3.Layer3Ranking;
import com.stellartrack.messaging.contract.layer3.Layer3RankingContract;
import com.stellartrack.messaging.correlation.RaceMarketCorrelator;
import com.stellartrack.messaging.domain.ErrorClass;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.messaging.domain.NormalizedLayerRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The real Layer 1/2/3 contracts against payloads shaped exactly as the producers publish them. */
class LayerContractsTest {

    private static final JsonCodec JSON = new JsonCodec(JsonMapper.builder().build());
    private static final RaceMarketCorrelator UNMAPPED = (card, race, horses) ->
            RaceMarketCorrelator.Resolution.unmapped(RaceMarketCorrelator.Status.VENUE_NOT_MAPPED, "test", null);

    private final LayerContract<Layer1Prediction> l1 = Layer1PredictionContract.create(JSON, UNMAPPED);
    private final LayerContract<Layer2StateChange> l2 = Layer2StateChangeContract.create(JSON);
    private final LayerContract<Layer3Ranking> l3 = Layer3RankingContract.create(JSON, UNMAPPED);
    private final LayerContractRegistry registry = LayerContractRegistryAccess.of(List.of(l1, l2, l3));

    private static InboundMessage l1Message(String body) {
        return message(Layer.LAYER1, body, "layer1.prediction", Map.of());
    }

    private static void assertInvalid(LayerContract<?> contract, InboundMessage message, ErrorClass errorClass,
            String detail) {
        assertThatThrownBy(() -> contract.apply(message)).isInstanceOfSatisfying(ContractViolationException.class, e -> {
            assertThat(e.errorClass()).isEqualTo(errorClass);
            assertThat(e.getMessage()).contains(detail);
        });
    }

    @Nested
    class Layer1 {

        @Test
        void acceptsPlainJsonWithoutTypeHeaderOnTheProducerRoutingKey() {
            InboundMessage message = l1Message(LayerSamples.layer1(CARD, 3, 7));
            assertThat(registry.resolve(message)).isSameAs(l1);

            NormalizedLayerRecord record = l1.apply(message);
            assertThat(record.contractVersion()).isEqualTo("l1-prediction-v1");
            assertThat(record.dedupKey()).startsWith("L1:1096-2025-08-01:3:").hasSize("L1:1096-2025-08-01:3:".length() + 64);
            assertThat(record.sourceEventAt()).as("Layer 1 has no event timestamp").isNull();
            assertThat(record.marketLinks()).as("no approved mapping, no links").isEmpty();
            assertThat(record.payload()).containsEntry("venueId", "1096").containsEntry("raceDate", "2025-08-01");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> runners = (List<Map<String, Object>>) record.payload().get("runners");
            assertThat(runners).extracting(r -> r.get("choice")).containsExactly(1, 2, 3, 4, 5, 6);
            assertThat(runners.getFirst()).containsEntry("horseNumber", 5).containsEntry("planet", "SUN")
                    .containsEntry("planetScore", 7).containsEntry("isFinal", true)
                    .containsEntry("odds", new BigDecimal("2.63"));
            assertThat(runners.get(2)).as("odds 0 = unknown").doesNotContainKey("odds");
            assertThat(runners.get(4)).containsEntry("isFinal", false).containsEntry("planet", "MOON");
        }

        @Test
        void acceptsTheReplayToolsDirectPublish() {
            InboundMessage replay = message(Layer.LAYER1, LayerSamples.layer1(CARD, 3, 7), "layer1-predictions-queue",
                    Map.of("__TypeId__", "com.kmss.racehitservice.dto.rabbit.Layer1PredictionMessage"));
            assertThat(registry.resolve(replay)).isSameAs(l1);
            String withNullOdds = LayerSamples.layer1(CARD, 3, 7).replace("\"odds\":5.5", "\"odds\":null")
                    .replace("\"raceDate\"", "\"metadata\":{\"totalRaces\":8},\"raceDate\"");
            assertThat(l1.apply(message(Layer.LAYER1, withNullOdds, "layer1-predictions-queue", Map.of())).dedupKey())
                    .startsWith("L1:");
        }

        @Test
        void doesNotClaimOtherRoutingKeysOrShapes() {
            assertThatThrownBy(() -> registry.resolve(message(Layer.LAYER1, LayerSamples.layer1(CARD, 3, 7), "race.data", Map.of())))
                    .isInstanceOfSatisfying(ContractViolationException.class,
                            e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.UNSUPPORTED_CONTRACT_VERSION));
            String raceData = "{\"cardId\":\"" + CARD + "\",\"raceNumber\":3,\"raceDate\":\"2025-08-01\",\"runnerNumbers\":[1,2],\"odds\":[]}";
            assertThatThrownBy(() -> registry.resolve(l1Message(raceData)))
                    .isInstanceOf(ContractViolationException.class);
        }

        @Test
        void identicalResendHasTheSameIdentityAndARevisionHasANewOne() {
            String original = LayerSamples.layer1(CARD, 3, 7);
            String revised = LayerSamples.layer1(CARD, 3, 9);
            assertThat(l1.apply(l1Message(original)).dedupKey()).isEqualTo(l1.apply(l1Message(original)).dedupKey());
            assertThat(l1.apply(l1Message(revised)).dedupKey()).isNotEqualTo(l1.apply(l1Message(original)).dedupKey());
        }

        @Test
        void rejectsRaceOneDuplicatesAndBadIdentifiers() {
            assertInvalid(l1, l1Message(LayerSamples.layer1(CARD, 1, 7)), ErrorClass.SCHEMA_VALIDATION, "raceNumber must be an integer >= 2");
            String duplicateHorse = LayerSamples.layer1(CARD, 3, 7).replace("{\"horseNumber\":1,\"choice\":2", "{\"horseNumber\":5,\"choice\":2");
            assertInvalid(l1, l1Message(duplicateHorse), ErrorClass.SCHEMA_VALIDATION, "horseNumber 5 appears more than once");
            String duplicateChoice = LayerSamples.layer1(CARD, 3, 7).replace("\"choice\":6", "\"choice\":5");
            assertInvalid(l1, l1Message(duplicateChoice), ErrorClass.SCHEMA_VALIDATION, "choice 5 appears more than once");
            assertInvalid(l1, l1Message(LayerSamples.layer1("venue-2025-08-01", 3, 7)), ErrorClass.SCHEMA_VALIDATION, "cardId");
            String emptyFinal = LayerSamples.layer1(CARD, 3, 7).replaceFirst("\"finalPrediction\":\\[.*?\\],\"otherRunners\"", "\"finalPrediction\":[],\"otherRunners\"");
            assertInvalid(l1, l1Message(emptyFinal), ErrorClass.SCHEMA_VALIDATION, "finalPrediction");
            String negativeOdds = LayerSamples.layer1(CARD, 3, 7).replace("\"odds\":12", "\"odds\":-1");
            assertInvalid(l1, l1Message(negativeOdds), ErrorClass.SCHEMA_VALIDATION, "odds.odds must be >= 0");
        }

        @Test
        void reportsMalformedBodiesAsMalformed() {
            InboundMessage broken = l1Message("{\"cardId\":");
            assertThat(registry.resolve(broken)).isSameAs(l1);
            assertInvalid(l1, broken, ErrorClass.MALFORMED_PAYLOAD, "JSON");
        }
    }

    @Nested
    class Layer2 {

        private final Instant at = Instant.parse("2025-08-01T13:45:10.123456789Z");

        @Test
        void acceptsSpringAmqpHashMapWithEpochSecondsAndArrayTimes() {
            InboundMessage message = message(Layer.LAYER2, LayerSamples.layer2EpochSeconds(CARD, 2, at), "state-change",
                    Map.of("__TypeId__", "java.util.HashMap", "__KeyTypeId__", "java.lang.String"));
            assertThat(registry.resolve(message)).isSameAs(l2);
            NormalizedLayerRecord record = l2.apply(message);
            assertThat(record.contractVersion()).isEqualTo("l2-planetary-state-v1");
            assertThat(record.sourceEventAt()).isEqualTo(at);
            assertThat(record.dedupKey()).startsWith("L2:1096-2025-08-01:2:");
            assertThat(record.payload()).containsEntry("timestamp", at.toString());

            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> states = (Map<String, Map<String, Object>>) record.payload().get("states");
            assertThat(states).containsOnlyKeys("MARS", "VENUS", "MERCURY", "MOON", "SUN");
            assertThat(states.get("MARS")).containsEntry("stateCode", "DOMINANT_CONTINUATION").containsEntry("strengthScore", 88)
                    .containsEntry("authorityLevel", "EXPLOSIVE").containsEntry("contextFlags", Map.of("is_in_planet", true, "out_streak", 1));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trend = (List<Map<String, Object>>) states.get("MARS").get("positionTrend");
            assertThat(trend.getFirst()).containsEntry("raceTime", "14:30").containsEntry("hora", "3");
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>) record.payload().get("raceCardContext");
            assertThat(context).containsEntry("scenario", "EQUA3").containsEntry("totalRaces", 8)
                    .containsEntry("inPlanets", List.of("MARS", "VENUS", "SUN"));
        }

        @Test
        void acceptsIsoTimestampsWithoutTypeHeader() {
            InboundMessage message = message(Layer.LAYER2, LayerSamples.layer2Iso(CARD, 2, at), "state-change", Map.of());
            assertThat(registry.resolve(message)).isSameAs(l2);
            NormalizedLayerRecord record = l2.apply(message);
            assertThat(record.sourceEventAt()).isEqualTo(at);
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> states = (Map<String, Map<String, Object>>) record.payload().get("states");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trend = (List<Map<String, Object>>) states.get("SUN").get("positionTrend");
            assertThat(trend.getFirst()).containsEntry("raceTime", "14:30");
        }

        @Test
        void rejectsUnknownPlanetsAndOutOfRangeStrength() {
            String body = LayerSamples.layer2EpochSeconds(CARD, 2, at);
            assertInvalid(l2, message(Layer.LAYER2, body.replace("\"SUN\":{", "\"PLUTO\":{"), "state-change", Map.of()),
                    ErrorClass.SCHEMA_VALIDATION, "unknown planet PLUTO");
            assertInvalid(l2, message(Layer.LAYER2, body.replace("\"strengthScore\":88", "\"strengthScore\":120"), "state-change", Map.of()),
                    ErrorClass.SCHEMA_VALIDATION, "strengthScore of MARS must be 0-100");
            assertInvalid(l2, message(Layer.LAYER2, body.replace("\"raceNumber\":2,\"timestamp\"", "\"raceNumber\":0,\"timestamp\""), "state-change", Map.of()),
                    ErrorClass.SCHEMA_VALIDATION, "raceNumber must be an integer >= 1");
        }
    }

    @Nested
    class Layer3 {

        private final Instant generatedAt = Instant.parse("2025-08-01T13:50:00.250Z");

        @Test
        void acceptsBothFlagSpellingsIdentically() {
            InboundMessage jsonNames = message(Layer.LAYER3, LayerSamples.layer3(CARD, 3, "LAYER1_RERANKED", generatedAt, false),
                    "layer3.reranked", Map.of("__TypeId__", LayerSamples.L3_TYPE));
            InboundMessage javaNames = message(Layer.LAYER3, LayerSamples.layer3(CARD, 3, "LAYER1_RERANKED", generatedAt, true),
                    "layer3.reranked", Map.of());
            assertThat(registry.resolve(jsonNames)).isSameAs(l3);
            assertThat(registry.resolve(javaNames)).isSameAs(l3);
            NormalizedLayerRecord a = l3.apply(jsonNames);
            NormalizedLayerRecord b = l3.apply(javaNames);
            assertThat(a.payload()).isEqualTo(b.payload());
            assertThat(a.dedupKey()).isEqualTo("L3:1096-2025-08-01:3:LAYER1_RERANKED:2025-08-01T13:50:00.250Z");
            assertThat(a.sourceEventAt()).isEqualTo(generatedAt);
            assertThat(a.payload()).containsEntry("napHorseNumber", 1).containsEntry("rerankPolicyVersion", "v1.0");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rankings = (List<Map<String, Object>>) a.payload().get("rankings");
            assertThat(rankings.getFirst()).containsEntry("top4", true).containsEntry("nap", true).containsEntry("planet", "MARS");
            assertThat(rankings.get(2)).as("null odds are omitted").doesNotContainKey("odds");
            assertThat(rankings.get(4)).containsEntry("top4", false);
        }

        @Test
        void republishWithNewGenerationTimeIsANewRevision() {
            String first = l3.apply(message(Layer.LAYER3, LayerSamples.layer3(CARD, 3, "STANDALONE", generatedAt, false), "layer3.reranked", Map.of())).dedupKey();
            String second = l3.apply(message(Layer.LAYER3, LayerSamples.layer3(CARD, 3, "STANDALONE", generatedAt.plusSeconds(5), false), "layer3.reranked", Map.of())).dedupKey();
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        void rejectsDuplicateRanksAndUnknownSources() {
            String body = LayerSamples.layer3(CARD, 3, "LAYER1_RERANKED", generatedAt, false);
            assertInvalid(l3, message(Layer.LAYER3, body.replace("\"finalRank\":2", "\"finalRank\":1"), "layer3.reranked", Map.of()),
                    ErrorClass.SCHEMA_VALIDATION, "finalRank 1 appears more than once");
            assertInvalid(l3, message(Layer.LAYER3, body.replace("\"finalRank\":5", "\"finalRank\":0"), "layer3.reranked", Map.of()),
                    ErrorClass.SCHEMA_VALIDATION, "finalRank must be an integer >= 1");
            assertInvalid(l3, message(Layer.LAYER3, LayerSamples.layer3(CARD, 3, "MANUAL", generatedAt, false), "layer3.reranked", Map.of()),
                    ErrorClass.SCHEMA_VALIDATION, "source must be one of");
        }
    }
}
