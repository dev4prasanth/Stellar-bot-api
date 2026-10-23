package com.stellartrack.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stellartrack.messaging.contract.LayerContract;
import com.stellartrack.messaging.contract.LayerSamples;
import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;

/**
 * Layer ingestion through a real RabbitMQ broker laid out as the producers lay it out: the shared topic exchange
 * {@code stellar-track-exchange} with the producer-owned queues and bindings Layer 1/2/3 declare. Phase 2 subscribes
 * with its own queues, so the producer queues must still receive every message. The framework checks (malformed,
 * oversized, unsupported version, reprocess) use a TEST-ONLY fixture contract on the Layer 1 queue.
 */
@Import(RabbitIngestionIT.RabbitTestConfig.class)
@TestPropertySource(properties = {
        "stellartrack.messaging.declare-owned-topology=true",
        "stellartrack.messaging.max-payload-bytes=16384",
        "stellartrack.messaging.retry.max-attempts=2",
        "stellartrack.messaging.retry.initial-interval=PT0.2S",
        "stellartrack.messaging.layers.LAYER1.enabled=true",
        "stellartrack.messaging.layers.LAYER1.queue=" + RabbitIngestionIT.QUEUE,
        "stellartrack.messaging.layers.LAYER1.exchange=" + RabbitIngestionIT.EXCHANGE,
        "stellartrack.messaging.layers.LAYER1.routing-keys=layer1.prediction,layer1-predictions-queue",
        "stellartrack.messaging.layers.LAYER1.declare-subscription=true",
        "stellartrack.messaging.layers.LAYER2.enabled=true",
        "stellartrack.messaging.layers.LAYER2.queue=stellartrack.phase2.layer2",
        "stellartrack.messaging.layers.LAYER2.exchange=" + RabbitIngestionIT.EXCHANGE,
        "stellartrack.messaging.layers.LAYER2.routing-keys=state-change",
        "stellartrack.messaging.layers.LAYER2.declare-subscription=true",
        "stellartrack.messaging.layers.LAYER3.enabled=true",
        "stellartrack.messaging.layers.LAYER3.queue=stellartrack.phase2.layer3",
        "stellartrack.messaging.layers.LAYER3.exchange=" + RabbitIngestionIT.EXCHANGE,
        "stellartrack.messaging.layers.LAYER3.routing-keys=layer3.reranked",
        "stellartrack.messaging.layers.LAYER3.declare-subscription=true"})
class RabbitIngestionIT extends IntegrationTest {

    static final String EXCHANGE = "stellar-track-exchange";
    static final String QUEUE = "stellartrack.phase2.layer1";
    static final String DLQ = "stellartrack.layer1.dlq";
    static final String L1_QUEUE = "layer1-predictions-queue";
    static final String L2_QUEUE = "planetary-state-queue";
    static final String L3_QUEUE = "reranked-predictions-queue";

    /** The producers' own topology, declared exactly as stellartrackbackend, Layer 2 and Layer 3 declare it. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RabbitTestConfig {

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbit() {
            return new RabbitMQContainer("rabbitmq:4-management-alpine");
        }

        @Bean
        TopicExchange stellarTrackExchange() {
            return new TopicExchange(EXCHANGE, true, false);
        }

        @Bean
        Queue layer1PredictionsQueue() {
            return new Queue(L1_QUEUE, true);
        }

        @Bean
        Queue planetaryStateQueue() {
            return new Queue(L2_QUEUE, true);
        }

        @Bean
        Queue rerankedPredictionsQueue() {
            return new Queue(L3_QUEUE, true);
        }

        @Bean
        Binding layer1PredictionBinding() {
            return BindingBuilder.bind(layer1PredictionsQueue()).to(stellarTrackExchange()).with("layer1.prediction");
        }

        @Bean
        Binding planetaryStateBinding() {
            return BindingBuilder.bind(planetaryStateQueue()).to(stellarTrackExchange()).with("state-change");
        }

        @Bean
        Binding rerankedBinding() {
            return BindingBuilder.bind(rerankedPredictionsQueue()).to(stellarTrackExchange()).with("layer3.reranked");
        }

        @Bean
        LayerContract<String[]> testOnlyLayer1Contract() {
            return IngestionUnitTest.fixtureContract(Layer.LAYER1);
        }
    }

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    RabbitAdmin admin;

    @Autowired
    JdbcTemplate jdbc;

    private void publish(String body, String version) {
        MessageProperties props = new MessageProperties();
        props.setContentType("text/plain");
        if (version != null) {
            props.setHeader("x-test-version", version);
        }
        rabbit.send("", QUEUE, new Message(body.getBytes(StandardCharsets.UTF_8), props));
    }

    /** Publishes like a producer: JSON, persistent, with exactly the given headers (none for Layer 1). */
    private void produce(String routingKey, String body, Map<String, String> headers) {
        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        headers.forEach(props::setHeader);
        rabbit.send(EXCHANGE, routingKey, new Message(body.getBytes(StandardCharsets.UTF_8), props));
    }

    private int receipts(String status, String dedupKey) {
        return jdbc.queryForObject("select count(*) from message_receipt where layer = 'LAYER1' and status = ?"
                + (dedupKey == null ? "" : " and dedup_key = '" + dedupKey + "'"), Integer.class, status);
    }

    private int receiptsLike(String layer, String status, String dedupPrefix) {
        return jdbc.queryForObject("select count(*) from message_receipt where layer = ? and status = ? and dedup_key like ?",
                Integer.class, layer, status, dedupPrefix + "%");
    }

    private int queued(String queue) {
        Properties info = admin.getQueueProperties(queue);
        return info == null ? -1 : ((Number) info.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }

    private JsonNode get(String token, String path) throws Exception {
        return mapper.readTree(mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void consumesLayer1To3AsPublishedWithoutStealingFromTheLayers() throws Exception {
        String card = "2001-2025-08-01";
        Instant now = Instant.now();
        int l1Before = queued(L1_QUEUE);
        int l2Before = queued(L2_QUEUE);
        int l3Before = queued(L3_QUEUE);

        // Layer 1: plain JSON on layer1.prediction, no __TypeId__ / message id / correlation id.
        produce("layer1.prediction", LayerSamples.layer1(card, 3, 7), Map.of());
        // Layer 2: Spring AMQP HashMap after race 2's result, epoch-second timestamps.
        produce("state-change", LayerSamples.layer2EpochSeconds(card, 2, now), Map.of("__TypeId__", LayerSamples.L2_TYPE,
                "__KeyTypeId__", "java.lang.String", "__ContentTypeId__", "java.lang.Object"));
        // Layer 3: ranking for race 3.
        produce("layer3.reranked", LayerSamples.layer3(card, 3, "LAYER1_RERANKED", now, false),
                Map.of("__TypeId__", LayerSamples.L3_TYPE));

        await(() -> receiptsLike("LAYER1", "ACCEPTED", "L1:" + card + ":3:") == 1
                && receiptsLike("LAYER2", "ACCEPTED", "L2:" + card + ":2:") == 1
                && receiptsLike("LAYER3", "ACCEPTED", "L3:" + card + ":3:LAYER1_RERANKED:") == 1);
        // Each layer has its own consumer, so the three projections complete independently.
        await(() -> jdbc.queryForObject("select count(*) from race_signal_l3_ranking where card_id = ?", Integer.class, card) == 1
                && jdbc.queryForObject("select count(*) from race_signal_l1_prediction where card_id = ?", Integer.class, card) == 1
                && jdbc.queryForObject("select count(*) from race_signal_planetary_state where card_id = ? and race_number = 2",
                        Integer.class, card) == 5
                && "EQUA3".equals(jdbc.queryForObject("select scenario from race_signal_card where card_id = ?", String.class, card)));

        // An identical Layer 1 resend is a duplicate; a revised prediction is a new accepted revision.
        produce("layer1.prediction", LayerSamples.layer1(card, 3, 7), Map.of());
        await(() -> receiptsLike("LAYER1", "DUPLICATE", "L1:" + card + ":3:") == 1);
        produce("layer1.prediction", LayerSamples.layer1(card, 3, 9), Map.of());
        await(() -> receiptsLike("LAYER1", "ACCEPTED", "L1:" + card + ":3:") == 2);
        await(() -> jdbc.queryForObject("select revision from race_signal_l1_prediction where card_id = ? and race_number = 3",
                Integer.class, card) == 2);

        // The replay tool's direct publish (routing key layer1-predictions-queue, its own type header) is accepted too.
        produce("layer1-predictions-queue", LayerSamples.layer1(card, 4, 5),
                Map.of("__TypeId__", "com.kmss.racehitservice.dto.rabbit.Layer1PredictionMessage"));
        await(() -> receiptsLike("LAYER1", "ACCEPTED", "L1:" + card + ":4:") == 1
                && jdbc.queryForObject("select count(*) from race_signal_l1_prediction where card_id = ? and race_number = 4",
                        Integer.class, card) == 1);

        // Every producer queue still received its own copy of every message: Phase 2 never competes for them.
        await(() -> queued(L1_QUEUE) == l1Before + 3 && queued(L2_QUEUE) == l2Before + 1 && queued(L3_QUEUE) == l3Before + 1);
        assertThat(rabbit.receive(L2_QUEUE, 5_000)).isNotNull();

        String token = bearer();
        JsonNode cards = get(token, "/api/v1/signals/cards?date=2025-08-01&venueId=2001");
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).path("layer1Races").asInt()).isEqualTo(2);
        assertThat(cards.get(0).path("layer2Races").asInt()).isEqualTo(1);
        assertThat(cards.get(0).path("layer3Races").asInt()).isEqualTo(1);
        assertThat(cards.get(0).path("totalRaces").asInt()).isEqualTo(8);
        assertThat(cards.get(0).path("inPlanets").toString()).isEqualTo("[\"MARS\",\"VENUS\",\"SUN\"]");
        assertThat(cards.get(0).path("outPlanets").toString()).isEqualTo("[\"MERCURY\",\"MOON\"]");
        assertThat(cards.get(0).path("lastRaceWinner").asInt()).isEqualTo(5);
        assertThat(cards.get(0).path("contextUpdatedAt").asString()).isNotBlank();

        JsonNode detail = get(token, "/api/v1/signals/cards/" + card);
        JsonNode races = detail.path("races");
        assertThat(races).hasSize(8);
        assertThat(races.get(0).path("layer1").asString()).isEqualTo("NOT_EXPECTED");
        assertThat(races.get(0).path("layer2").asString()).isEqualTo("NOT_EXPECTED");
        assertThat(races.get(0).path("layer3").asString()).isEqualTo("NOT_EXPECTED");
        assertThat(races.get(2).path("layer1").asString()).isEqualTo("RECEIVED");
        assertThat(races.get(2).path("layer2").asString()).isEqualTo("RECEIVED");
        assertThat(races.get(2).path("layer3").asString()).isEqualTo("RECEIVED");
        assertThat(races.get(2).path("layer1Revision").asInt()).isEqualTo(2);
        assertThat(races.get(3).path("layer3").asString()).isEqualTo("WAITING");

        JsonNode race = get(token, "/api/v1/signals/cards/" + card + "/races/3");
        assertThat(race.at("/layer2/inputRaceNumber").asInt()).isEqualTo(2);
        assertThat(race.at("/layer2/states")).hasSize(5);
        assertThat(race.at("/layer2/states/0/planet").asString()).isEqualTo("MARS");
        assertThat(race.at("/layer2/states/0/sourceEventAt").asString()).isNotBlank();
        assertThat(race.at("/layer1/runners/0/planetScore").asInt()).isEqualTo(9);
        assertThat(race.at("/mapping/status").asString()).isEqualTo("VENUE_NOT_MAPPED");
        JsonNode top = race.at("/layer3/rankings/0/runners/0");
        assertThat(top.path("horseNumber").asInt()).isEqualTo(1);
        assertThat(top.path("finalRank").asInt()).isEqualTo(1);
        assertThat(top.path("layer1Choice").asInt()).isEqualTo(2);
        assertThat(top.path("rankChange").asInt()).isEqualTo(1);
        assertThat(top.path("nap").asBoolean()).isTrue();
        assertThat(top.path("betfairMapping").asString()).isEqualTo("VENUE_NOT_MAPPED");
        assertThat(race.at("/trace/LAYER1/receiptId").asString()).isNotBlank();
        assertThat(race.at("/trace/LAYER3_LAYER1_RERANKED/receiptId").asString()).isNotBlank();

        JsonNode raceOne = get(token, "/api/v1/signals/cards/" + card + "/races/1");
        assertThat(raceOne.at("/layer1/status").asString()).isEqualTo("NOT_EXPECTED");
        assertThat(raceOne.at("/layer3/status").asString()).isEqualTo("NOT_EXPECTED");
    }

    @Test
    void validatesPersistsDeduplicatesAndDeadLetters() throws Exception {
        String key = "rec-" + System.nanoTime();
        publish(key + "|42", "fixture-1");
        await(() -> receipts("ACCEPTED", key) == 1);
        assertThat(jdbc.queryForObject("select count(*) from layer_source_record where dedup_key = ?", Integer.class, key))
                .isEqualTo(1);

        publish(key + "|42", "fixture-1"); // redelivered/duplicate source message
        await(() -> receipts("DUPLICATE", key) == 1);
        assertThat(jdbc.queryForObject("select count(*) from layer_source_record where dedup_key = ?", Integer.class, key))
                .isEqualTo(1);

        int invalidBefore = receipts("INVALID", null);
        publish("no-separator", "fixture-1");
        await(() -> receipts("INVALID", null) == invalidBefore + 1);
        Message dead = rabbit.receive(DLQ, 5_000);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().<String>getHeader("x-st-error-class")).isEqualTo("MALFORMED_PAYLOAD");

        publish("x|1", "v99");
        await(() -> jdbc.queryForObject("select count(*) from message_receipt where error_class = 'UNSUPPORTED_CONTRACT_VERSION'",
                Integer.class) >= 1);

        publish("y".repeat(20_000), "fixture-1");
        await(() -> jdbc.queryForObject("select count(*) from message_receipt where error_class = 'PAYLOAD_TOO_LARGE'",
                Integer.class) >= 1);
        assertThat(jdbc.queryForObject("select count(*) from message_receipt where error_class = 'PAYLOAD_TOO_LARGE' and payload_raw is not null",
                Integer.class)).isZero();
    }

    @Test
    void reportsLayerStatusAndReprocessesQuarantinedReceipts() throws Exception {
        String token = bearer();
        publish("again-no-separator", "fixture-1");
        await(() -> receipts("INVALID", null) >= 1);
        JsonNode layers = get(token, "/api/v1/ingestion/layers");
        for (int i = 0; i < 3; i++) {
            assertThat(layers.get(i).at("/feed/state").asString()).isIn("FRESH", "MISSING");
            assertThat(layers.get(i).path("exchange").asString()).isEqualTo(EXCHANGE);
        }
        assertThat(layers.get(0).path("contractVersions").toString()).contains("l1-prediction-v1");
        assertThat(layers.get(1).path("contractVersions").toString()).contains("l2-planetary-state-v1");
        assertThat(layers.get(2).path("contractVersions").toString()).contains("l3-ranking-v1");

        String receiptId = jdbc.queryForObject("select id::text from message_receipt where status = 'INVALID' and layer = 'LAYER1' limit 1", String.class);
        JsonNode outcome = mapper.readTree(mvc.perform(MockMvcRequestBuilders.post("/api/v1/ingestion/receipts/" + receiptId + "/reprocess")
                .header("Authorization", token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(outcome.path("status").asString()).isEqualTo("INVALID");
        assertThat(jdbc.queryForObject("select status from message_receipt where id = ?::uuid", String.class, receiptId))
                .isEqualTo("REPROCESSED");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Condition not met within 15 seconds");
    }
}
