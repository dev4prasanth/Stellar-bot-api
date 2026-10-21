package com.stellartrack.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stellartrack.capital.application.CapitalService;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.market.application.MarketCatalogService;
import com.stellartrack.order.application.OrderReconciliationService;
import com.stellartrack.order.port.PlacementResult;
import com.stellartrack.order.port.ProviderMarketSettlement;
import com.stellartrack.order.port.ProviderSettlement;
import com.stellartrack.support.IntegrationTest;
import com.stellartrack.support.MarketFixtures;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Manual Mode end to end against real PostgreSQL with a TEST-ONLY exchange double: review → confirm → durable
 * intent → placement; idempotency; cross-request duplicate prevention; ambiguous outcome reconciliation;
 * settlement, correction and report/dashboard consistency.
 */
class OrderFlowIT extends IntegrationTest {

    @Autowired
    MarketCatalogService catalog;
    @Autowired
    CapitalService capital;
    @Autowired
    OrderReconciliationService reconciliation;
    @Autowired
    JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        exchange.reset();
        capital.refresh();
        token = bearer();
    }

    private String seedMarket() {
        String marketId = MarketFixtures.newMarketId();
        exchange.markets.put(marketId, MarketFixtures.market(marketId, Instant.now().plus(20, ChronoUnit.MINUTES), MarketType.WIN));
        exchange.prices.put(marketId, MarketFixtures.open(marketId, "4.0", "4.1"));
        catalog.upsert(List.of(exchange.markets.get(marketId)));
        return marketId;
    }

    private JsonNode post(String path, Object body, int expectedStatus) throws Exception {
        String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return response.isBlank() ? null : mapper.readTree(response);
    }

    private JsonNode get(String path) throws Exception {
        return mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static Map<String, Object> staking() {
        return Map.of("method", "FIXED_STAKE", "amount", 5);
    }

    private JsonNode reviewAndPlace(String marketId, String key, int expectedStatus) throws Exception {
        JsonNode review = post("/api/v1/orders/review", Map.of("marketId", marketId, "selectionId", 101,
                "side", BetSide.BACK.name(), "staking", staking()), 200);
        assertThat(review.path("violations")).isEmpty();
        assertThat(review.path("stake").path("stake").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(review.path("stake").path("estimatedGrossProfit").decimalValue()).isEqualByComparingTo("15.00");
        return post("/api/v1/orders/place", Map.of("reviewToken", review.path("reviewToken").asString(),
                "idempotencyKey", key, "acceptDelayedData", false, "staking", staking()), expectedStatus);
    }

    @Test
    void eachWayBackTicketCountsBothPartsAndLayIsRefused() throws Exception {
        String marketId = MarketFixtures.newMarketId();
        exchange.markets.put(marketId, MarketFixtures.eachWayMarket(marketId, Instant.now().plus(20, ChronoUnit.MINUTES), "5"));
        exchange.prices.put(marketId, MarketFixtures.open(marketId, "6.0", "6.2"));
        catalog.upsert(List.of(exchange.markets.get(marketId)));

        JsonNode back = post("/api/v1/orders/review", Map.of("marketId", marketId, "selectionId", 101,
                "side", BetSide.BACK.name(), "staking", staking()), 200);
        assertThat(back.path("marketType").asString()).isEqualTo("EACH_WAY");
        assertThat(back.path("violations")).isEmpty();
        // 5 each way at 6.0, 1/5 odds: 10 at risk; 25 (win part) + 5 (place part at 2.0) if it wins.
        assertThat(back.at("/stake/stake").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(back.at("/stake/exposure").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(back.at("/stake/estimatedGrossProfit").decimalValue()).isEqualByComparingTo("30.00");
        JsonNode placed = post("/api/v1/orders/place", Map.of("reviewToken", back.path("reviewToken").asString(),
                "idempotencyKey", UUID.randomUUID().toString(), "acceptDelayedData", false, "staking", staking()), 200);
        assertThat(placed.path("status").asString()).isEqualTo("PLACED");
        assertThat(exchange.placed).anyMatch(c -> c.marketId().equals(marketId) && c.size().compareTo(new BigDecimal("5.00")) == 0);

        JsonNode lay = post("/api/v1/orders/review", Map.of("marketId", marketId, "selectionId", 101,
                "side", BetSide.LAY.name(), "staking", staking()), 200);
        assertThat(lay.at("/stake/valid").asBoolean()).isFalse();
        assertThat(lay.at("/stake/rejectionReason").asString()).isEqualTo("Each-Way bets can only be Back bets");
        assertThat(lay.hasNonNull("reviewToken")).isFalse();
    }

    @Test
    void backDutchPlacesEveryRunnerForEqualProfitAndRevokesTheGroup() throws Exception {
        exchange.placementBehaviour = exchange::acceptUnmatched;
        String marketId = seedMarket(); // Favourite backs at 4.0, Outsider at 12.0
        JsonNode review = post("/api/v1/orders/dutch/review", Map.of("marketId", marketId,
                "selections", List.of(Map.of("selectionId", 101), Map.of("selectionId", 102)), "totalStake", 20), 200);
        assertThat(review.path("problems")).isEmpty();
        assertThat(review.path("placeable").asBoolean()).isTrue();
        assertThat(review.at("/legs/0/stake").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(review.at("/legs/1/stake").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(review.path("minProfit").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(review.path("maxProfit").decimalValue()).isEqualByComparingTo("40.00");

        JsonNode outcome = post("/api/v1/orders/dutch/place", Map.of("acceptDelayedData", false, "legs", List.of(
                Map.of("reviewToken", review.at("/legs/0/review/reviewToken").asString(), "idempotencyKey", UUID.randomUUID().toString()),
                Map.of("reviewToken", review.at("/legs/1/review/reviewToken").asString(), "idempotencyKey", UUID.randomUUID().toString()))), 200);
        assertThat(outcome.path("complete").asBoolean()).isTrue();
        String groupId = outcome.path("groupId").asString();

        JsonNode rows = get("/api/v1/orders?open=true&marketId=" + marketId).path("items");
        assertThat(rows).hasSize(2);
        rows.forEach(r -> assertThat(r.path("dutchGroupId").asString()).isEqualTo(groupId));

        JsonNode revoked = post("/api/v1/orders/dutch/" + groupId + "/revoke", Map.of(), 200);
        assertThat(revoked.path("legs")).hasSize(2);
        revoked.path("legs").forEach(l -> assertThat(l.path("outcome").asString()).isEqualTo("SUCCESS"));
    }

    @Test
    void dutchIsRefusedWhenAStakeFallsBelowTheMinimumOrOutsideAWinMarket() throws Exception {
        String marketId = seedMarket();
        JsonNode tooSmall = post("/api/v1/orders/dutch/review", Map.of("marketId", marketId,
                "selections", List.of(Map.of("selectionId", 101), Map.of("selectionId", 102)), "totalStake", 2), 200);
        assertThat(tooSmall.path("placeable").asBoolean()).isFalse();
        assertThat(tooSmall.path("problems").toString()).contains("below Betfair's minimum stake");

        String place = MarketFixtures.newMarketId();
        exchange.markets.put(place, MarketFixtures.market(place, Instant.now().plus(20, ChronoUnit.MINUTES), MarketType.PLACE));
        exchange.prices.put(place, MarketFixtures.open(place, "2.0", "2.1"));
        catalog.upsert(List.of(exchange.markets.get(place)));
        JsonNode refused = post("/api/v1/orders/dutch/review", Map.of("marketId", place,
                "selections", List.of(Map.of("selectionId", 101), Map.of("selectionId", 102)), "totalStake", 20), 422);
        assertThat(refused.toString()).contains("Win markets only");
    }

    @Test
    void unmatchedBetIsRevocableAndRevokedOnBetfair() throws Exception {
        exchange.placementBehaviour = exchange::acceptUnmatched;
        String marketId = seedMarket();
        String betId = reviewAndPlace(marketId, UUID.randomUUID().toString(), 200).path("betId").asString();

        JsonNode open = get("/api/v1/orders?open=true&marketId=" + marketId);
        assertThat(open.at("/items/0/revocable").asBoolean()).isTrue();
        assertThat(open.at("/items/0/origin").asString()).isEqualTo("MANUAL");
        // A Race Market ticket is not a bot bet.
        assertThat(get("/api/v1/orders?open=true&origin=AUTOMATIC&marketId=" + marketId).path("items")).isEmpty();

        JsonNode revoked = post("/api/v1/orders/bets/" + betId + "/cancel", Map.of(), 200);
        assertThat(revoked.path("outcome").asString()).isEqualTo("SUCCESS");
    }

    @Test
    void revokeRevalidatesWithBetfairAndRefusesABetMatchedMeanwhile() throws Exception {
        exchange.placementBehaviour = exchange::acceptUnmatched;
        String marketId = seedMarket();
        String betId = reviewAndPlace(marketId, UUID.randomUUID().toString(), 200).path("betId").asString();
        assertThat(get("/api/v1/orders?open=true&marketId=" + marketId).at("/items/0/revocable").asBoolean()).isTrue();

        // Betfair matches the bet after the screen was drawn: the revoke re-reads it and sends nothing.
        exchange.matchFully(betId);
        JsonNode refused = post("/api/v1/orders/bets/" + betId + "/cancel", Map.of(), 422);
        assertThat(refused.toString()).contains("already been fully matched");

        JsonNode row = get("/api/v1/orders?open=true&marketId=" + marketId).at("/items/0");
        assertThat(row.path("revocable").asBoolean()).isFalse();
        assertThat(row.path("revokeBlockedReason").asString())
                .isEqualTo("This bet cannot be revoked because it has already been fully matched.");
    }

    @Test
    void manualTicketIsPlacedOnceAndDuplicatesAreRefused() throws Exception {
        String marketId = seedMarket();
        String key = UUID.randomUUID().toString();
        JsonNode placed = reviewAndPlace(marketId, key, 200);
        assertThat(placed.path("status").asString()).isEqualTo("PLACED");
        assertThat(placed.path("betId").asString()).isNotBlank();

        // Same idempotency key: the original outcome is returned and nothing is resubmitted.
        JsonNode review = post("/api/v1/orders/review", Map.of("marketId", marketId, "selectionId", 101,
                "side", "BACK", "staking", staking()), 200);
        assertThat(review.path("violations").toString()).contains("DUPLICATE_ENTRY");

        JsonNode repeated = post("/api/v1/orders/place", Map.of("reviewToken", review.path("reviewToken").asString(),
                "idempotencyKey", key, "staking", staking()), 200);
        assertThat(repeated.path("intentId").asString()).isEqualTo(placed.path("intentId").asString());
        assertThat(exchange.placed).hasSize(1);

        // New request for the same runner/side: refused before reaching Betfair.
        JsonNode duplicate = post("/api/v1/orders/place", Map.of("reviewToken", review.path("reviewToken").asString(),
                "idempotencyKey", UUID.randomUUID().toString(), "staking", staking()), 422);
        assertThat(duplicate.path("violations").toString()).contains("DUPLICATE_ENTRY");
        assertThat(exchange.placed).hasSize(1);

        // Every stage is recorded and traceable from the order history.
        JsonNode detail = get("/api/v1/orders/" + placed.path("intentId").asString());
        assertThat(detail.path("attempts")).hasSize(1);
        assertThat(detail.path("intent").path("recommendationId").asString()).isNotBlank();
    }

    @Test
    void tamperedOrExpiredReviewIsRejected() throws Exception {
        String marketId = seedMarket();
        JsonNode review = post("/api/v1/orders/review", Map.of("marketId", marketId, "selectionId", 101,
                "side", "BACK", "staking", staking()), 200);
        String tampered = review.path("reviewToken").asString() + "x";
        JsonNode result = post("/api/v1/orders/place", Map.of("reviewToken", tampered,
                "idempotencyKey", UUID.randomUUID().toString(), "staking", staking()), 409);
        assertThat(result.path("code").asString()).isEqualTo("REVIEW_INVALID");
        assertThat(exchange.placed).isEmpty();
    }

    @Test
    void ambiguousOutcomeIsReconciledNotRetried() throws Exception {
        String marketId = seedMarket();
        exchange.placementBehaviour = c -> {
            exchange.acceptAll(c); // Betfair did accept it...
            return PlacementResult.unknown("Simulated timeout"); // ...but the response was lost.
        };
        JsonNode placed = reviewAndPlace(marketId, UUID.randomUUID().toString(), 200);
        assertThat(placed.path("status").asString()).isEqualTo("UNKNOWN_OUTCOME");

        reconciliation.reconcileOpenAndAmbiguous();

        String status = jdbc.queryForObject("select status from order_intent where id = ?", String.class,
                UUID.fromString(placed.path("intentId").asString()));
        assertThat(status).isEqualTo("PLACED");
        assertThat(exchange.placed).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from bet_order where intent_id = ?", Integer.class,
                UUID.fromString(placed.path("intentId").asString()))).isEqualTo(1);
    }

    @Test
    void settlementsAndCorrectionsReconcileWithReportsAndDashboard() throws Exception {
        JsonNode before = get("/api/v1/reports/summary");
        String marketId = seedMarket();
        JsonNode placed = reviewAndPlace(marketId, UUID.randomUUID().toString(), 200);
        String betId = placed.path("betId").asString();

        exchange.cleared.add(settlement(betId, marketId, "15.00"));
        exchange.clearedMarkets.add(new ProviderMarketSettlement(marketId, Instant.now(), new BigDecimal("15.00"), new BigDecimal("0.75")));
        reconciliation.reconcileCleared();
        reconciliation.reconcileCleared(); // idempotent: same provider record is not stored twice

        JsonNode after = get("/api/v1/reports/summary");
        assertThat(delta(after, before, "/settlements/won")).isEqualTo(1);
        assertThat(delta(after, before, "/orders/placed")).isEqualTo(1);
        assertThat(after.at("/realized/grossPnl").decimalValue().subtract(before.at("/realized/grossPnl").decimalValue()))
                .isEqualByComparingTo("15.00");
        assertThat(after.at("/realized/commission").decimalValue().subtract(before.at("/realized/commission").decimalValue()))
                .isEqualByComparingTo("0.75");

        // Provider correction: a new revision supersedes the old one; totals are not double counted.
        exchange.cleared.clear();
        exchange.cleared.add(settlement(betId, marketId, "14.00"));
        reconciliation.reconcileCleared();
        JsonNode corrected = get("/api/v1/reports/summary");
        assertThat(delta(corrected, before, "/settlements/won")).isEqualTo(1);
        assertThat(delta(corrected, before, "/settlements/corrected")).isEqualTo(1);
        assertThat(corrected.at("/realized/grossPnl").decimalValue().subtract(before.at("/realized/grossPnl").decimalValue()))
                .isEqualByComparingTo("14.00");

        // Dashboard tiles use the same records and definitions as Reports.
        JsonNode dashboard = get("/api/v1/dashboard");
        assertThat(dashboard.at("/summary/settlements/won").asLong()).isEqualTo(corrected.at("/settlements/won").asLong());
        assertThat(dashboard.at("/summary/realized/grossPnl").decimalValue()).isEqualByComparingTo(corrected.at("/realized/grossPnl").decimalValue());

        // Drill-down shows decision inputs, execution, both revisions and the authoritative profit.
        JsonNode calc = get("/api/v1/reports/bets/" + betId + "/calculations");
        assertThat(calc.path("settlementRevisions")).hasSize(2);
        assertThat(calc.at("/estimateVersusActual/actualSettledProfit").decimalValue()).isEqualByComparingTo("14.00");
        assertThat(calc.path("decision").path("snapshot").path("strategyCode").asString()).isEqualTo("MANUAL_SELECTION");

        String csv = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/reports/export?dataset=SETTLED").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains(betId).contains("14.00");
    }

    private static ProviderSettlement settlement(String betId, String marketId, String profit) {
        return new ProviderSettlement(betId, marketId, 101, BetSide.BACK, "SETTLED", "WON", Instant.now(),
                new BigDecimal("4.0"), new BigDecimal("4.0"), false, new BigDecimal("5.00"), BigDecimal.ZERO,
                new BigDecimal(profit), Instant.now(), Instant.now(), null, "STELLARTRACK");
    }

    private static long delta(JsonNode after, JsonNode before, String pointer) {
        return after.at(pointer).asLong() - before.at(pointer).asLong();
    }
}
