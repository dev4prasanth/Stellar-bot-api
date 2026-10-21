package com.stellartrack.watch;

import static org.assertj.core.api.Assertions.assertThat;

import com.stellartrack.automation.application.AutomationEvaluator;
import com.stellartrack.automation.application.AutomationService;
import com.stellartrack.automation.persistence.AutomationRunRepository;
import com.stellartrack.capital.application.CapitalService;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.common.domain.OperatingMode;
import com.stellartrack.market.application.MarketCatalogService;
import com.stellartrack.market.application.MarketResultService;
import com.stellartrack.order.application.OrderPlacementService;
import com.stellartrack.order.application.PlacementCommand;
import com.stellartrack.order.application.PlacementOutcome;
import com.stellartrack.order.domain.OrderPurpose;
import com.stellartrack.order.port.PersistenceType;
import com.stellartrack.staking.domain.StakeCalculation;
import com.stellartrack.staking.domain.StakingMethodType;
import com.stellartrack.support.ConfigurationFixtures;
import com.stellartrack.support.IntegrationTest;
import com.stellartrack.support.MarketFixtures;
import com.stellartrack.watch.application.WatchEvaluator;
import com.stellartrack.watch.application.WatchSessionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Watch Mode end to end: starting Watch pauses real betting, running configurations place simulated bets that are
 * matched against the book and settled from the official result into the virtual capital, and nothing reaches the
 * exchange's order API or the live records.
 */
class WatchFlowIT extends IntegrationTest {

    @Autowired
    MarketCatalogService catalog;
    @Autowired
    MarketResultService marketResults;
    @Autowired
    CapitalService capital;
    @Autowired
    AutomationService automation;
    @Autowired
    AutomationEvaluator liveEvaluator;
    @Autowired
    AutomationRunRepository runs;
    @Autowired
    WatchSessionService watchSessions;
    @Autowired
    WatchEvaluator watchEvaluator;
    @Autowired
    OrderPlacementService placement;
    @Autowired
    JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        exchange.reset();
        capital.refresh();
        token = bearer();
        stopWatchIfRunning();
        postJson(token, "/api/v1/automation/trading-mode", Map.of("mode", "MANUAL"), 200);
    }

    @AfterEach
    void tearDown() throws Exception {
        stopWatchIfRunning();
    }

    @Test
    void watchSimulatesBetsAndSettlesThemWithoutTouchingRealBetting() throws Exception {
        UUID configId = createConfiguration();
        String marketId = seedMarket();
        // Watch cannot start while a configuration runs in live trading: live trading is stopped first.
        postJson(token, "/api/v1/automation/configurations/" + configId + "/start", Map.of(), 200);
        JsonNode refused = postJson(token, "/api/v1/watch/sessions", Map.of("name", "it-watch", "capitalSource", "CUSTOM",
                "customAmount", 1000, "configurationIds", List.of(configId)), 409);
        assertThat(refused.toString()).contains("STOP_BEFORE_MODE_CHANGE");
        postJson(token, "/api/v1/automation/live/stop", Map.of(), 200);
        long liveSettledBefore = count("select count(*) from v_settled_bet");

        JsonNode started = postJson(token, "/api/v1/watch/sessions", Map.of("name", "it-watch", "capitalSource", "CUSTOM",
                "customAmount", 1000, "configurationIds", List.of(configId)), 200);
        UUID sessionId = UUID.fromString(started.at("/session/id").asString());
        assertThat(getJson(token, "/api/v1/watch/status").path("tradingMode").asString()).isEqualTo("WATCH");
        // Manual/Automatic cannot be chosen while Watch runs: Watch must be stopped.
        postJson(token, "/api/v1/automation/trading-mode", Map.of("mode", "AUTOMATIC"), 409);

        // No live recommendation or order.
        assertThat(count("select count(*) from recommendation where market_id = '" + marketId + "'")).isZero();

        // Watch evaluates the same configuration and places a simulated bet on the favourite.
        var session = watchSessions.require(sessionId);
        watchEvaluator.running(session).forEach(c -> watchEvaluator.tick(session, c));
        Map<String, Object> bet = jdbc.queryForMap(
                "select id, selection_id, size_matched, matching_status from watch_bet where market_id = ?", marketId);
        assertThat(bet).containsEntry("selection_id", 101L).containsEntry("matching_status", "MATCHED");
        assertThat((BigDecimal) bet.get("size_matched")).isEqualByComparingTo("2.00");
        assertThat(exchange.placed).noneMatch(c -> c.marketId().equals(marketId));
        // Fully matched: the Revoke option is disabled and a revoke is refused.
        JsonNode openBets = getJson(token, "/api/v1/watch/bets?sessionId=" + sessionId + "&status=OPEN");
        assertThat(openBets.at("/items/0/revocable").asBoolean()).isFalse();
        assertThat(openBets.at("/items/0/revokeBlockedReason").asString()).contains("already been fully matched");
        assertThat(postJson(token, "/api/v1/watch/bets/" + bet.get("id") + "/revoke", Map.of(), 422).toString())
                .contains("already been fully matched");

        // A real order is refused while Watch runs.
        PlacementOutcome real = placement.submit(realCommand(marketId));
        assertThat(real.status()).isEqualTo(PlacementOutcome.Status.RISK_REJECTED);
        assertThat(real.violations()).anyMatch(v -> v.code().equals("WATCH_MODE_ACTIVE"));

        // The race is run: the favourite wins. 2.00 @ 3.5 → +5.00 gross, 5% commission → +4.75 net.
        jdbc.update("update market set market_start_time = now() - interval '10 minutes' where market_id = ?", marketId);
        exchange.prices.put(marketId, MarketFixtures.closed(marketId, "WINNER", "LOSER"));
        marketResults.captureClosedMarkets();
        UUID betId = (UUID) bet.get("id");
        awaitTrue(() -> count("select count(*) from watch_bet_settlement where bet_id = '" + betId + "' and is_current") == 1);
        Map<String, Object> settled = jdbc.queryForMap("select result, gross_profit, commission, net_profit "
                + "from watch_bet_settlement where bet_id = ? and is_current", betId);
        assertThat(settled).containsEntry("result", "WON");
        assertThat((BigDecimal) settled.get("net_profit")).isEqualByComparingTo("4.75");

        JsonNode capitalView = getJson(token, "/api/v1/watch/sessions/" + sessionId + "/capital");
        assertThat(capitalView.at("/capital/currentCapital").decimalValue()).isEqualByComparingTo("1004.75");
        JsonNode report = getJson(token, "/api/v1/watch/reports?sessionId=" + sessionId);
        assertThat(report.at("/totals/won").asLong()).isEqualTo(1);
        assertThat(report.at("/totals/netProfit").decimalValue()).isEqualByComparingTo("4.75");
        JsonNode bets = getJson(token, "/api/v1/watch/bets?sessionId=" + sessionId + "&status=SETTLED");
        assertThat(bets.path("items").toString()).contains(betId.toString());

        // Live records are untouched by Watch.
        assertThat(count("select count(*) from v_settled_bet")).isEqualTo(liveSettledBefore);

        JsonNode stopped = postJson(token, "/api/v1/watch/sessions/" + sessionId + "/stop", Map.of("resumeMode", "MANUAL"), 200);
        assertThat(stopped.at("/session/status").asString()).isEqualTo("STOPPED");
        assertThat(getJson(token, "/api/v1/automation/status").at("/control/tradingMode").asString()).isEqualTo("MANUAL");
    }

    @Test
    void startingWatchExpiresProposalsAwaitingApproval() throws Exception {
        UUID configId = createConfiguration();
        String marketId = seedMarket();
        UUID liveRun = UUID.fromString(postJson(token, "/api/v1/automation/configurations/" + configId + "/start", Map.of(), 200)
                .path("id").asString());
        var run = runs.findById(liveRun).orElseThrow();
        liveEvaluator.tick(run, automation.session(run)); // MANUAL: the favourite waits for approval
        assertThat(count("select count(*) from recommendation where market_id = '" + marketId + "' and status = 'PENDING_APPROVAL'"))
                .isEqualTo(1);
        automation.stop(configId, "before Watch", "system"); // a plain stop leaves its proposals waiting

        postJson(token, "/api/v1/watch/sessions", Map.of("capitalSource", "CUSTOM", "customAmount", 500,
                "configurationIds", List.of(configId)), 200);

        assertThat(count("select count(*) from recommendation where market_id = '" + marketId + "' and status = 'EXPIRED'"))
                .isEqualTo(1);
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private void stopWatchIfRunning() throws Exception {
        if (watchSessions.active().isPresent()) {
            postJson(token, "/api/v1/watch/sessions/" + watchSessions.active().get().getId() + "/stop",
                    Map.of("resumeMode", "MANUAL"), 200);
        }
    }

    private UUID createConfiguration() throws Exception {
        JsonNode created = postJson(token, "/api/v1/configurations", Map.of("name", "it-watch-" + UUID.randomUUID(),
                "settings", ConfigurationFixtures.marketCriteriaBack("2")), 201);
        return UUID.fromString(created.path("id").asString());
    }

    private String seedMarket() {
        String marketId = MarketFixtures.newMarketId();
        exchange.markets.put(marketId, MarketFixtures.market(marketId, Instant.now().plus(20, ChronoUnit.MINUTES), MarketType.WIN));
        exchange.prices.put(marketId, MarketFixtures.open(marketId, "3.5", "3.6"));
        catalog.upsert(List.of(exchange.markets.get(marketId)));
        return marketId;
    }

    private static PlacementCommand realCommand(String marketId) {
        BigDecimal stake = new BigDecimal("2.00");
        StakeCalculation calculation = new StakeCalculation(StakingMethodType.FIXED_STAKE, BetSide.BACK, new BigDecimal("3.5"),
                stake, null, stake, new BigDecimal("5.00"), null, null, List.of(), List.of(), true, null);
        return new PlacementCommand(OperatingMode.MANUAL, OperatingMode.MANUAL, OrderPurpose.ENTRY, null, null, null, marketId,
                101L, BetSide.BACK, new BigDecimal("3.5"), calculation, PersistenceType.LAPSE, "it-watch-" + UUID.randomUUID(),
                "test-admin", new BigDecimal("3.5"), false, null);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
