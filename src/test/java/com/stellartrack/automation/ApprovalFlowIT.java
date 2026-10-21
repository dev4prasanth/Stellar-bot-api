package com.stellartrack.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stellartrack.automation.application.AutomationEvaluator;
import com.stellartrack.automation.application.AutomationService;
import com.stellartrack.automation.persistence.AutomationRunRepository;
import com.stellartrack.capital.application.CapitalService;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.market.application.MarketCatalogService;
import com.stellartrack.market.application.MarketResultService;
import com.stellartrack.support.IntegrationTest;
import com.stellartrack.support.MarketFixtures;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

class ApprovalFlowIT extends IntegrationTest {

    @Autowired
    MarketCatalogService catalog;
    @Autowired
    MarketResultService marketResults;
    @Autowired
    CapitalService capital;
    @Autowired
    AutomationService automation;
    @Autowired
    AutomationEvaluator evaluator;
    @Autowired
    AutomationRunRepository runs;
    @Autowired
    JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        exchange.reset();
        capital.refresh();
        token = bearer();
        send("/api/v1/automation/trading-mode", Map.of("mode", "MANUAL"), 200);
    }

    @Test
    void rejectedProposalIsNeverSubmittedAndItsOutcomeIsEvaluated() throws Exception {
        JsonNode before = get("/api/v1/reports/summary");
        UUID configId = createConfiguration(true);
        String marketId = seedMarket();
        UUID runId = start(configId);
        tick(runId);

        JsonNode pending = pendingFor(marketId);
        assertThat(pending.path("selectionId").asLong()).isEqualTo(101); // favourite (price rank 1)
        assertThat(skippedCount(marketId)).isEqualTo(1); // outsider: NO BET because max one selection per race

        JsonNode detail = get("/api/v1/recommendations/" + pending.path("id").asString());
        assertThat(detail.at("/snapshot/strategyCode").asString()).isEqualTo("MARKET_CRITERIA");
        assertThat(detail.at("/snapshot/marketState/proposedPrice").decimalValue()).isEqualByComparingTo("3.5");

        send("/api/v1/recommendations/" + pending.path("id").asString() + "/reject",
                Map.of("reasonCode", "PRICE_UNATTRACTIVE", "note", "Too short"), 200);
        assertThat(exchange.placed).noneMatch(c -> c.marketId().equals(marketId));

        // Race is run: the rejected favourite won → the rejected BACK proposal is a favorable selection outcome.
        jdbc.update("update market set market_start_time = now() - interval '10 minutes' where market_id = ?", marketId);
        exchange.prices.put(marketId, MarketFixtures.closed(marketId, "WINNER", "LOSER"));
        marketResults.captureClosedMarkets();
        await(() -> jdbc.queryForObject("select count(*) from decision_outcome_evaluation where recommendation_id = ?",
                Integer.class, UUID.fromString(pending.path("id").asString())) > 0);

        JsonNode after = get("/api/v1/reports/summary");
        assertThat(after.at("/rejected/favorable").asLong() - before.at("/rejected/favorable").asLong()).isEqualTo(1);
        // A favorable rejected selection is never a won bet nor realized P&L.
        assertThat(after.at("/settlements/won").asLong()).isEqualTo(before.at("/settlements/won").asLong());
        assertThat(after.at("/realized/grossPnl").decimalValue()).isEqualByComparingTo(before.at("/realized/grossPnl").decimalValue());

        JsonNode rejections = get("/api/v1/reports/manual-rejections?classification=FAVORABLE");
        assertThat(rejections.path("items").toString()).contains(pending.path("id").asString());
        automation.stop(configId, "test done", "system");
    }

    @Test
    void approvedProposalIsRevalidatedAndSubmitted() throws Exception {
        UUID configId = createConfiguration(true);
        String marketId = seedMarket();
        UUID runId = start(configId);
        tick(runId);
        JsonNode pending = pendingFor(marketId);

        send("/api/v1/recommendations/" + pending.path("id").asString() + "/approve", Map.of(), 200);
        await(() -> exchange.placed.stream().anyMatch(c -> c.marketId().equals(marketId)));
        await(() -> "PLACED".equals(jdbc.queryForObject(
                "select status from order_intent where recommendation_id = ?", String.class,
                UUID.fromString(pending.path("id").asString()))));
        // Confirmed by the administrator → a Manual-mode bet, while its origin stays the configuration pipeline.
        Map<String, Object> intent = jdbc.queryForMap("select mode, trading_mode from order_intent where recommendation_id = ?",
                UUID.fromString(pending.path("id").asString()));
        assertThat(intent).containsEntry("trading_mode", "MANUAL").containsEntry("mode", "AUTOMATIC");
        automation.stop(configId, "test done", "system");
    }

    @Test
    void automaticTradingModePlacesWithoutApprovalEvenIfTheConfigurationAskedForIt() throws Exception {
        JsonNode control = send("/api/v1/automation/trading-mode", Map.of("mode", "AUTOMATIC"), 200);
        assertThat(control.path("tradingMode").asString()).isEqualTo("AUTOMATIC");
        UUID configId = createConfiguration(true); // the superseded per-configuration flag is ignored
        String marketId = seedMarket();
        UUID runId = start(configId);
        tick(runId);

        assertThat(exchange.placed).anyMatch(c -> c.marketId().equals(marketId));
        Map<String, Object> intent = jdbc.queryForMap(
                "select oi.trading_mode, oi.status from order_intent oi join recommendation r on r.id = oi.recommendation_id"
                        + " where r.market_id = ?", marketId);
        assertThat(intent).containsEntry("trading_mode", "AUTOMATIC").containsEntry("status", "PLACED");
        assertThat(get("/api/v1/recommendations/approval-queue").toString()).doesNotContain(marketId);

        JsonNode orders = get("/api/v1/orders?mode=AUTOMATIC&from=" + Instant.now().minus(1, ChronoUnit.HOURS)
                + "&to=" + Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(orders.path("items").toString()).contains(marketId).contains("\"mode\":\"AUTOMATIC\"");
        automation.stop(configId, "test done", "system");
        send("/api/v1/automation/trading-mode", Map.of("mode", "MANUAL"), 200);
    }

    @Test
    void tradingModeCannotChangeWhileAConfigurationRunsAndStoppingWithdrawsItsProposals() throws Exception {
        UUID configId = createConfiguration(false);
        String marketId = seedMarket();
        UUID runId = start(configId);
        tick(runId);
        JsonNode pending = pendingFor(marketId); // MANUAL: held for approval although the configuration said "no"

        JsonNode refused = send("/api/v1/automation/trading-mode", Map.of("mode", "AUTOMATIC"), 409);
        assertThat(refused.toString()).contains("STOP_BEFORE_MODE_CHANGE");
        send("/api/v1/automation/trading-mode", Map.of("mode", "BOGUS"), 400);

        JsonNode stopped = send("/api/v1/automation/live/stop", Map.of("reason", "it"), 200);
        assertThat(stopped.path("stoppedConfigurations").toString()).contains(configId.toString());
        assertThat(state(configId)).isEqualTo("STOPPED");
        // The proposal still awaiting approval is withdrawn and never submitted.
        assertThat(jdbc.queryForObject("select status from recommendation where id = ?", String.class,
                UUID.fromString(pending.path("id").asString()))).isEqualTo("EXPIRED");
        assertThat(exchange.placed).noneMatch(c -> c.marketId().equals(marketId));

        send("/api/v1/automation/trading-mode", Map.of("mode", "AUTOMATIC"), 200);
        assertThat(get("/api/v1/automation/status").at("/control/tradingMode").asString()).isEqualTo("AUTOMATIC");
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'TRADING_MODE_CHANGED'",
                Integer.class)).isPositive();
        send("/api/v1/automation/trading-mode", Map.of("mode", "MANUAL"), 200);
    }

    @Test
    void onlyOneConfigurationRunsAtATimeAndSwitchingIsOneStep() throws Exception {
        UUID first = createConfiguration(false);
        UUID second = createConfiguration(false);
        start(first);
        JsonNode refused = send("/api/v1/automation/configurations/" + second + "/start", Map.of(), 409);
        assertThat(refused.toString()).contains("ANOTHER_CONFIGURATION_RUNNING");

        send("/api/v1/automation/live/switch", Map.of("configurationId", second.toString()), 200);
        assertThat(state(first)).isEqualTo("STOPPED");
        assertThat(state(second)).isEqualTo("RUNNING");

        // A target that cannot start changes nothing: the active configuration keeps running.
        jdbc.update("update global_control set stop_all = true");
        try {
            JsonNode blocked = send("/api/v1/automation/live/switch", Map.of("configurationId", first.toString()), 422);
            assertThat(blocked.toString()).contains("Stop All is engaged");
            assertThat(state(second)).isEqualTo("RUNNING");
            assertThat(state(first)).isEqualTo("STOPPED");
        } finally {
            jdbc.update("update global_control set stop_all = false");
            automation.stop(second, "test done", "system");
        }
    }

    @Test
    void stopAllStopsAutomationAndRestartNeverResumes() throws Exception {
        UUID configId = createConfiguration(false);
        start(configId);
        send("/api/v1/automation/stop-all", Map.of("reason", "test"), 200);
        assertThat(state(configId)).isEqualTo("STOPPED");
        JsonNode blocked = send("/api/v1/automation/configurations/" + configId + "/start", Map.of(), 422);
        assertThat(blocked.toString()).contains("Stop All is engaged");
        send("/api/v1/automation/stop-all/release", Map.of(), 200);

        start(configId);
        automation.run(null); // simulates application restart recovery
        assertThat(state(configId)).isEqualTo("STOPPED");
        assertThat(jdbc.queryForObject("select state_reason from bot_configuration where id = ?", String.class, configId))
                .contains("not resumed");
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private UUID createConfiguration(boolean approval) throws Exception {
        Map<String, Object> settings = new HashMap<>();
        settings.put("selection", Map.of("source", "MARKET_CRITERIA", "maxSelectionsPerRace", 1));
        settings.put("market", Map.of("marketType", "WIN", "side", "BACK"));
        settings.put("staking", Map.of("method", "FIXED_STAKE", "amount", 2));
        settings.put("timing", Map.of("entryWindowStartMinutesBefore", 60, "entryWindowEndMinutesBefore", 1,
                "allowInPlay", false, "maxInPlayBetDelaySeconds", 0, "persistenceType", "LAPSE"));
        settings.put("price", Map.of("minOdds", 1.5, "maxOdds", 50, "maxPriceMoveTicks", 2));
        settings.put("limits", Map.of("maxEntriesPerRace", 1, "maxEntriesPerDay", 50, "maxEntriesPerSession", 20,
                "maxExposurePerRace", 100, "maxTotalOpenExposure", 500, "minAvailableBalance", 10));
        settings.put("stops", Map.of());
        settings.put("trading", Map.of("dutching", false, "priceMovementDetection", false, "equalisation", false,
                "closeOut", false, "freeBetPositioning", false));
        settings.put("approval", Map.of("manualApprovalRequired", approval, "approvalTimeoutSeconds", 600));
        JsonNode created = send("/api/v1/configurations", Map.of("name", "it-" + UUID.randomUUID(), "settings", settings), 201);
        return UUID.fromString(created.path("id").asString());
    }

    private String seedMarket() {
        String marketId = MarketFixtures.newMarketId();
        exchange.markets.put(marketId, MarketFixtures.market(marketId, Instant.now().plus(20, ChronoUnit.MINUTES), MarketType.WIN));
        exchange.prices.put(marketId, MarketFixtures.open(marketId, "3.5", "3.6"));
        catalog.upsert(List.of(exchange.markets.get(marketId)));
        return marketId;
    }

    private UUID start(UUID configId) throws Exception {
        return UUID.fromString(send("/api/v1/automation/configurations/" + configId + "/start", Map.of(), 200)
                .path("id").asString());
    }

    private void tick(UUID runId) {
        var run = runs.findById(runId).orElseThrow();
        evaluator.tick(run, automation.session(run));
    }

    private JsonNode pendingFor(String marketId) throws Exception {
        for (JsonNode item : get("/api/v1/recommendations/approval-queue")) {
            if (marketId.equals(item.path("marketId").asString())) {
                return item;
            }
        }
        throw new AssertionError("No pending recommendation for " + marketId);
    }

    private int skippedCount(String marketId) {
        return jdbc.queryForObject("select count(*) from recommendation where market_id = ? and status = 'SKIPPED'",
                Integer.class, marketId);
    }

    private String state(UUID configId) {
        return jdbc.queryForObject("select operational_state from bot_configuration where id = ?", String.class, configId);
    }

    private JsonNode get(String path) throws Exception {
        return mapper.readTree(mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode send(String path, Object body, int expected) throws Exception {
        String content = mvc.perform(MockMvcRequestBuilders.post(path).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return content.isBlank() ? mapper.createObjectNode() : mapper.readTree(content);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Condition not met within 10 seconds");
    }
}
