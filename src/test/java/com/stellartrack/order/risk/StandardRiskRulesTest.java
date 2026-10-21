package com.stellartrack.order.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.stellartrack.capital.application.CapitalService.FundsView;
import com.stellartrack.common.api.Freshness;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.common.domain.OperatingMode;
import com.stellartrack.configuration.application.ConfigurationService.ResolvedConfiguration;
import com.stellartrack.configuration.domain.BotConfigurationSettings;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.order.application.PlacementCommand;
import com.stellartrack.order.domain.OrderPurpose;
import com.stellartrack.order.port.PersistenceType;
import com.stellartrack.staking.domain.StakeCalculation;
import com.stellartrack.staking.domain.StakingMethodType;
import com.stellartrack.staking.domain.StakingSettings;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StandardRiskRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-12T13:40:00Z");
    private final PreTradeValidator validator = new PreTradeValidator();

    private static BotConfigurationSettings settings(int maxPerRace, String stopLoss, Integer lossStreak) {
        return new BotConfigurationSettings(
                new BotConfigurationSettings.Selection(BotConfigurationSettings.SelectionSourceType.MARKET_CRITERIA, null, 1),
                new BotConfigurationSettings.Market(MarketType.WIN, BetSide.BACK, null, null), null,
                new StakingSettings(StakingMethodType.FIXED_STAKE, new BigDecimal("10"), null, null, null, null, null, null),
                new BotConfigurationSettings.Timing(30, 2, false, 0, PersistenceType.LAPSE),
                new BotConfigurationSettings.Price(new BigDecimal("1.5"), new BigDecimal("20"), 2),
                new BotConfigurationSettings.Limits(maxPerRace, 50, 20, new BigDecimal("100"), new BigDecimal("500"), new BigDecimal("50")),
                new BotConfigurationSettings.Stops(null, stopLoss == null ? null : new BigDecimal(stopLoss), null, null, lossStreak, null),
                new BotConfigurationSettings.CapitalAllocation(new BigDecimal("1000")),
                BotConfigurationSettings.Trading.none(),
                new BotConfigurationSettings.Approval(false, 60));
    }

    private static StakeCalculation stake(String amount) {
        BigDecimal s = new BigDecimal(amount);
        return new StakeCalculation(StakingMethodType.FIXED_STAKE, BetSide.BACK, new BigDecimal("4.0"), s, null, s,
                s.multiply(new BigDecimal("3")), null, null, List.of(), List.of(), true, null);
    }

    private static PreTradeContext context(OperatingMode mode, BotConfigurationSettings settings, BigDecimal reviewedPrice,
            String bestBack, boolean stopAll, Freshness.State freshness, boolean duplicate, int raceEntries,
            String sessionPnl, int lossStreak, String available) {
        return context(mode, settings, reviewedPrice, bestBack, stopAll, freshness, duplicate, raceEntries, sessionPnl,
                lossStreak, available, false, false);
    }

    private static PreTradeContext context(OperatingMode mode, BotConfigurationSettings settings, BigDecimal reviewedPrice,
            String bestBack, boolean stopAll, Freshness.State freshness, boolean duplicate, int raceEntries,
            String sessionPnl, int lossStreak, String available, boolean watchModeActive, boolean simulated) {
        ResolvedConfiguration config = settings == null ? null : new ResolvedConfiguration(UUID.randomUUID(), "cfg", 1, settings);
        PlacementCommand command = new PlacementCommand(mode, mode, OrderPurpose.ENTRY, null, null, config, "1.1", 7L,
                BetSide.BACK, new BigDecimal("4.0"), stake("10"), PersistenceType.LAPSE, "key-1", "admin", reviewedPrice,
                false, null);
        MarketSummary market = new MarketSummary("1.1", "WIN", MarketType.WIN, Instant.parse("2026-10-12T14:00:00Z"), "e1",
                "Ascot", "Ascot", "GB", "Flat", null, List.of());
        MarketPrices prices = new MarketPrices("1.1", "OPEN", false, false, 0, 1, 8, new BigDecimal("1000"), 3L, NOW,
                MarketPrices.PriceSource.POLL, List.of(new MarketPrices.RunnerPrices(7L, "ACTIVE", null, null, null,
                        List.of(new MarketPrices.PriceLevel(new BigDecimal(bestBack), new BigDecimal("100"))),
                        List.of(new MarketPrices.PriceLevel(new BigDecimal("4.1"), new BigDecimal("100"))), null)));
        FundsView funds = new FundsView("GBP", new BigDecimal(available), BigDecimal.ZERO, null, null, null, null,
                new Freshness(Freshness.State.LIVE, NOW, "test", null));
        return new PreTradeContext(command, NOW, true, stopAll, market, prices,
                new Freshness(freshness, NOW, "test", null), Optional.of(funds), duplicate, BigDecimal.ZERO,
                BigDecimal.ZERO, raceEntries, 0, 0, new BigDecimal(sessionPnl), lossStreak, 0,
                watchModeActive, simulated);
    }

    private List<String> codes(PreTradeContext ctx) {
        return validator.validate(ctx).stream().map(RiskViolation::code).toList();
    }

    @Test
    void validAutomaticEntryPasses() {
        assertThat(codes(context(OperatingMode.AUTOMATIC, settings(1, null, null), null, "4.0", false,
                Freshness.State.LIVE, false, 0, "0", 0, "500"))).isEmpty();
    }

    @Test
    void stopAllBlocksAutomaticButNotManual() {
        assertThat(codes(context(OperatingMode.AUTOMATIC, settings(1, null, null), null, "4.0", true,
                Freshness.State.LIVE, false, 0, "0", 0, "500"))).contains("STOP_ALL_ENGAGED");
        assertThat(codes(context(OperatingMode.MANUAL, null, new BigDecimal("4.0"), "4.0", true,
                Freshness.State.LIVE, false, 0, "0", 0, "500"))).doesNotContain("STOP_ALL_ENGAGED");
    }

    @Test
    void delayedDataBlocksAutomaticEntries() {
        assertThat(codes(context(OperatingMode.AUTOMATIC, settings(1, null, null), null, "4.0", false,
                Freshness.State.DELAYED, false, 0, "0", 0, "500"))).contains("PRICE_DATA_DELAYED");
    }

    @Test
    void duplicateEntryAndRaceLimitAreEnforced() {
        assertThat(codes(context(OperatingMode.AUTOMATIC, settings(1, null, null), null, "4.0", false,
                Freshness.State.LIVE, true, 1, "0", 0, "500"))).contains("DUPLICATE_ENTRY", "MAX_ENTRIES_PER_RACE");
    }

    @Test
    void adverseManualPriceMoveRequiresReconfirmation() {
        List<RiskViolation> violations = validator.validate(context(OperatingMode.MANUAL, null, new BigDecimal("4.0"),
                "3.8", false, Freshness.State.LIVE, false, 0, "0", 0, "500"));
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.code()).isEqualTo("PRICE_MOVED");
            assertThat(v.requiresReconfirmation()).isTrue();
        });
        // A better price for a BACK bet needs no reconfirmation.
        assertThat(codes(context(OperatingMode.MANUAL, null, new BigDecimal("4.0"), "4.2", false,
                Freshness.State.LIVE, false, 0, "0", 0, "500"))).isEmpty();
    }

    @Test
    void fundsAndMinimumBalanceAreChecked() {
        assertThat(codes(context(OperatingMode.AUTOMATIC, settings(1, null, null), null, "4.0", false,
                Freshness.State.LIVE, false, 0, "0", 0, "55"))).contains("MIN_BALANCE");
        assertThat(codes(context(OperatingMode.AUTOMATIC, settings(1, null, null), null, "4.0", false,
                Freshness.State.LIVE, false, 0, "0", 0, "5"))).contains("INSUFFICIENT_FUNDS");
    }

    @Test
    void sessionStopsPreventNewEntries() {
        assertThat(codes(context(OperatingMode.AUTOMATIC, settings(1, "40", null), null, "4.0", false,
                Freshness.State.LIVE, false, 0, "-40", 0, "500"))).contains("STOP_LOSS_REACHED");
        assertThat(codes(context(OperatingMode.AUTOMATIC, settings(1, null, 3), null, "4.0", false,
                Freshness.State.LIVE, false, 0, "0", 3, "500"))).contains("CONSECUTIVE_LOSS_STOP");
    }

    @Test
    void outsideEntryWindowIsRejectedForAutomatic() {
        PreTradeContext ctx = context(OperatingMode.AUTOMATIC, settings(1, null, null), null, "4.0", false,
                Freshness.State.LIVE, false, 0, "0", 0, "500");
        PreTradeContext early = new PreTradeContext(ctx.command(), Instant.parse("2026-10-12T12:00:00Z"), true, false,
                ctx.market(), ctx.prices(), ctx.priceFreshness(), ctx.funds(), false, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                0, 0, BigDecimal.ZERO, 0, 0, false, false);
        assertThat(codes(early)).contains("OUTSIDE_ENTRY_WINDOW");
    }

    @Test
    void watchModeBlocksEveryRealEntry() {
        assertThat(codes(context(OperatingMode.AUTOMATIC, settings(1, null, null), null, "4.0", false,
                Freshness.State.LIVE, false, 0, "0", 0, "500", true, false))).contains("WATCH_MODE_ACTIVE");
        assertThat(codes(context(OperatingMode.MANUAL, null, new BigDecimal("4.0"), "4.0", false,
                Freshness.State.LIVE, false, 0, "0", 0, "500", true, false))).contains("WATCH_MODE_ACTIVE");
    }

    @Test
    void simulatedWatchContextAcceptsDelayedDataAndIsNotBlockedByWatchModeOrStopAll() {
        assertThat(codes(context(OperatingMode.AUTOMATIC, settings(1, null, null), null, "4.0", true,
                Freshness.State.DELAYED, false, 0, "0", 0, "500", true, true))).isEmpty();
        assertThat(codes(context(OperatingMode.AUTOMATIC, settings(1, null, null), null, "4.0", false,
                Freshness.State.STALE, false, 0, "0", 0, "500", true, true))).contains("PRICE_DATA_STALE");
    }
}
