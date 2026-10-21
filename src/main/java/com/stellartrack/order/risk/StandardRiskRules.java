package com.stellartrack.order.risk;

import com.stellartrack.common.api.Freshness;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.money.PriceLadder;
import com.stellartrack.configuration.domain.BotConfigurationSettings;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.order.domain.OrderPurpose;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The standard pre-trade checks, each small and independent. Every placement path (manual, approved, automatic)
 * runs all of them; rules that only apply to automatic entries say so explicitly.
 */
public final class StandardRiskRules {

    /** Violations that end an unattended session (automation run or Watch configuration) rather than skip one entry. */
    public static final Set<String> SESSION_STOP_CODES = Set.of("STOP_PROFIT_REACHED", "STOP_LOSS_REACHED",
            "STOP_PROFIT_PERCENT_REACHED", "STOP_LOSS_PERCENT_REACHED", "CONSECUTIVE_LOSS_STOP",
            "CONSECUTIVE_WIN_STOP", "MAX_ENTRIES_PER_SESSION");

    private StandardRiskRules() {
    }

    public static List<RiskRule> all() {
        return List.of(
                rule("TRADING_MODE_WATCH", c -> !c.simulated() && c.watchModeActive()
                        && c.command().purpose() == OrderPurpose.ENTRY
                        ? fail("WATCH_MODE_ACTIVE", "Watch Mode is running: real betting is paused") : ok()),
                rule("EXCHANGE_AVAILABLE", c -> c.exchangeAvailable() ? ok()
                        : fail("EXCHANGE_UNAVAILABLE", "Betfair integration is not configured or not available")),
                rule("STOP_ALL", c -> c.isAutomatic() && c.stopAllEngaged() && !c.simulated()
                        ? fail("STOP_ALL_ENGAGED", "Stop All is engaged; no new automatic entries are submitted") : ok()),
                rule("MARKET_TYPE", StandardRiskRules::marketType),
                rule("MARKET_OPEN", StandardRiskRules::marketOpen),
                rule("ENTRY_WINDOW", StandardRiskRules::entryWindow),
                rule("RUNNER_ACTIVE", c -> c.runner().filter(MarketPrices.RunnerPrices::isActive).isPresent() ? ok()
                        : fail("RUNNER_NOT_ACTIVE", "The runner is not active (removed or not found)")),
                rule("PRICE_FRESHNESS", StandardRiskRules::priceFreshness),
                rule("PRICE_VALID", StandardRiskRules::priceValid),
                rule("PRICE_MOVEMENT", StandardRiskRules::priceMovement),
                rule("STAKE_VALID", c -> c.command().stake() != null && c.command().stake().valid() ? ok()
                        : fail("STAKE_INVALID", c.command().stake() == null ? "No stake calculation"
                                : c.command().stake().rejectionReason())),
                rule("FUNDS", StandardRiskRules::funds),
                rule("DUPLICATE_ENTRY", c -> c.command().purpose() == OrderPurpose.ENTRY && c.duplicateLiveEntry()
                        ? fail("DUPLICATE_ENTRY", "An entry on this runner and side is already live (any mode or configuration)")
                        : ok()),
                rule("ENTRY_LIMITS", StandardRiskRules::entryLimits),
                rule("EXPOSURE_LIMITS", StandardRiskRules::exposureLimits),
                rule("CAPITAL_ALLOCATION", StandardRiskRules::capitalAllocation),
                rule("STOP_RULES", StandardRiskRules::stopRules));
    }

    private static Optional<RiskViolation> marketType(PreTradeContext c) {
        return c.settings()
                .filter(s -> s.market().marketType() != c.market().marketType())
                .map(s -> RiskViolation.of("MARKET_TYPE", "MARKET_TYPE_MISMATCH",
                        "Configuration trades " + s.market().marketType() + " markets but this is a " + c.market().marketType() + " market"));
    }

    private static Optional<RiskViolation> marketOpen(PreTradeContext c) {
        if (c.prices() == null || !c.prices().isOpen()) {
            return fail("MARKET_NOT_OPEN", "Market is " + (c.prices() == null ? "unknown" : c.prices().status()));
        }
        if (c.prices().inPlay()) {
            boolean allowed = c.settings().map(s -> s.timing().allowInPlay()).orElse(true);
            if (!allowed) {
                return fail("IN_PLAY_NOT_ALLOWED", "Market is in-play and the configuration allows pre-race entries only");
            }
            Integer delay = c.prices().betDelaySeconds();
            int maxDelay = c.settings().map(s -> s.timing().maxInPlayBetDelaySeconds()).orElse(Integer.MAX_VALUE);
            if (delay != null && delay > maxDelay) {
                return fail("IN_PLAY_DELAY_TOO_LONG", "Betfair in-play bet delay " + delay + "s exceeds the configured " + maxDelay + "s");
            }
        }
        return ok();
    }

    private static Optional<RiskViolation> entryWindow(PreTradeContext c) {
        if (!c.isAutomatic() || c.settings().isEmpty() || (c.prices() != null && c.prices().inPlay())) {
            return ok();
        }
        BotConfigurationSettings.Timing timing = c.settings().get().timing();
        Duration untilStart = Duration.between(c.now(), c.market().startTime());
        long minutes = untilStart.toMinutes();
        if (untilStart.isNegative() || minutes > timing.entryWindowStartMinutesBefore()
                || minutes < timing.entryWindowEndMinutesBefore()) {
            return fail("OUTSIDE_ENTRY_WINDOW", "Outside the entry window (" + timing.entryWindowStartMinutesBefore()
                    + " to " + timing.entryWindowEndMinutesBefore() + " minutes before the scheduled start)");
        }
        return ok();
    }

    private static Optional<RiskViolation> priceFreshness(PreTradeContext c) {
        Freshness f = c.priceFreshness();
        if (f.state() == Freshness.State.LIVE) {
            return ok();
        }
        if (f.state() == Freshness.State.DELAYED && !c.isAutomatic() && c.command().acceptDelayedData()) {
            return ok();
        }
        if (f.state() == Freshness.State.DELAYED && c.simulated()) {
            return ok(); // Watch: simulated bets on delayed data, compensated by the fill model's slippage
        }
        return fail("PRICE_DATA_" + f.state(), "Market data is " + f.state()
                + (f.state() == Freshness.State.DELAYED ? " (automatic entries require live data; manual entries require acknowledgement)" : ""));
    }

    private static Optional<RiskViolation> priceValid(PreTradeContext c) {
        BigDecimal price = c.command().price();
        if (price == null || !PriceLadder.isValid(price)) {
            return fail("PRICE_NOT_ON_LADDER", "Price is not a valid Betfair price");
        }
        return c.settings().flatMap(s -> {
            BotConfigurationSettings.Price limits = s.price();
            if (limits.minOdds() != null && price.compareTo(limits.minOdds()) < 0
                    || limits.maxOdds() != null && price.compareTo(limits.maxOdds()) > 0) {
                return Optional.of(RiskViolation.of("PRICE_VALID", "PRICE_OUTSIDE_RANGE",
                        "Price " + price + " is outside the allowed odds range"));
            }
            return Optional.empty();
        });
    }

    /**
     * Manual/approved orders: if the best available price has moved against the confirmed price by more than the
     * allowed number of ticks, the administrator must review and confirm again. Better prices need no reconfirmation.
     */
    private static Optional<RiskViolation> priceMovement(PreTradeContext c) {
        BigDecimal reviewed = c.command().reviewedPrice();
        if (reviewed == null || c.runner().isEmpty()) {
            return ok();
        }
        MarketPrices.RunnerPrices runner = c.runner().get();
        Optional<MarketPrices.PriceLevel> best = c.command().side() == BetSide.BACK ? runner.bestBack() : runner.bestLay();
        if (best.isEmpty()) {
            return Optional.of(RiskViolation.reconfirm("PRICE_MOVEMENT", "NO_PRICE_AVAILABLE",
                    "No price is currently available on this side"));
        }
        int ticks = PriceLadder.ticksBetween(reviewed, best.get().price());
        int adverse = c.command().side() == BetSide.BACK ? -ticks : ticks;
        int allowed = c.settings().map(s -> s.price().maxPriceMoveTicks()).orElse(0);
        if (adverse > allowed) {
            return Optional.of(RiskViolation.reconfirm("PRICE_MOVEMENT", "PRICE_MOVED",
                    "Best price moved from " + reviewed + " to " + best.get().price() + " (" + adverse
                            + " ticks against you; allowed " + allowed + ")"));
        }
        return ok();
    }

    private static Optional<RiskViolation> funds(PreTradeContext c) {
        if (c.funds().isEmpty() || c.funds().get().freshness().state() != Freshness.State.LIVE) {
            return fail("FUNDS_UNKNOWN", "Current account funds are unavailable or stale");
        }
        BigDecimal available = c.funds().get().availableToBet();
        BigDecimal minimumRemaining = c.settings().map(s -> s.limits().minAvailableBalance()).orElse(null);
        BigDecimal after = available.subtract(c.newExposure());
        if (after.signum() < 0) {
            return fail("INSUFFICIENT_FUNDS", "Exposure " + c.newExposure() + " exceeds available balance " + available);
        }
        if (minimumRemaining != null && after.compareTo(minimumRemaining) < 0) {
            return fail("MIN_BALANCE", "Available balance would fall below the configured minimum " + minimumRemaining);
        }
        return ok();
    }

    private static Optional<RiskViolation> entryLimits(PreTradeContext c) {
        if (c.settings().isEmpty() || c.command().purpose() != OrderPurpose.ENTRY) {
            return ok();
        }
        BotConfigurationSettings.Limits limits = c.settings().get().limits();
        if (c.configurationRaceEntries() >= limits.maxEntriesPerRace()) {
            return fail("MAX_ENTRIES_PER_RACE", "Maximum entries per race reached (" + limits.maxEntriesPerRace() + ")");
        }
        if (c.configurationDayEntries() >= limits.maxEntriesPerDay()) {
            return fail("MAX_ENTRIES_PER_DAY", "Maximum entries per day reached (" + limits.maxEntriesPerDay() + ")");
        }
        if (c.isAutomatic() && c.configurationSessionEntries() >= limits.maxEntriesPerSession()) {
            return fail("MAX_ENTRIES_PER_SESSION", "Maximum entries per session reached (" + limits.maxEntriesPerSession() + ")");
        }
        return ok();
    }

    private static Optional<RiskViolation> exposureLimits(PreTradeContext c) {
        return c.settings().flatMap(s -> {
            BotConfigurationSettings.Limits limits = s.limits();
            if (limits.maxExposurePerRace() != null
                    && c.configurationRaceExposure().add(c.newExposure()).compareTo(limits.maxExposurePerRace()) > 0) {
                return Optional.of(RiskViolation.of("EXPOSURE_LIMITS", "MAX_RACE_EXPOSURE",
                        "Race exposure would exceed " + limits.maxExposurePerRace()));
            }
            if (limits.maxTotalOpenExposure() != null
                    && c.configurationOpenExposure().add(c.newExposure()).compareTo(limits.maxTotalOpenExposure()) > 0) {
                return Optional.of(RiskViolation.of("EXPOSURE_LIMITS", "MAX_OPEN_EXPOSURE",
                        "Open exposure would exceed " + limits.maxTotalOpenExposure()));
            }
            return Optional.empty();
        });
    }

    private static Optional<RiskViolation> capitalAllocation(PreTradeContext c) {
        return c.settings()
                .map(BotConfigurationSettings::capital)
                .filter(capital -> capital != null && capital.allocatedAmount() != null)
                .filter(capital -> c.configurationOpenExposure().add(c.newExposure()).compareTo(capital.allocatedAmount()) > 0)
                .map(capital -> RiskViolation.of("CAPITAL_ALLOCATION", "ALLOCATION_EXCEEDED",
                        "Open exposure would exceed the configuration's capital allocation " + capital.allocatedAmount()));
    }

    /** Profit/loss and sequence stops for the current automation session. Evaluated before every new entry. */
    private static Optional<RiskViolation> stopRules(PreTradeContext c) {
        if (!c.isAutomatic() || c.settings().isEmpty()) {
            return ok();
        }
        BotConfigurationSettings.Stops stops = c.settings().get().stops();
        BigDecimal pnl = c.sessionSettledProfit();
        if (stops.stopProfitAmount() != null && pnl.compareTo(stops.stopProfitAmount()) >= 0) {
            return fail("STOP_PROFIT_REACHED", "Session profit " + pnl + " reached the profit stop");
        }
        if (stops.stopLossAmount() != null && pnl.negate().compareTo(stops.stopLossAmount()) >= 0) {
            return fail("STOP_LOSS_REACHED", "Session loss " + pnl.negate() + " reached the loss stop");
        }
        BigDecimal bank = c.command().session() == null ? null : c.command().session().bankAtStart();
        if (bank != null && bank.signum() > 0) {
            BigDecimal percent = pnl.multiply(BigDecimal.valueOf(100)).divide(bank, 4, java.math.RoundingMode.HALF_EVEN);
            if (stops.stopProfitPercent() != null && percent.compareTo(stops.stopProfitPercent()) >= 0) {
                return fail("STOP_PROFIT_PERCENT_REACHED", "Session profit " + percent + "% reached the profit stop");
            }
            if (stops.stopLossPercent() != null && percent.negate().compareTo(stops.stopLossPercent()) >= 0) {
                return fail("STOP_LOSS_PERCENT_REACHED", "Session loss " + percent.negate() + "% reached the loss stop");
            }
        }
        if (stops.consecutiveLossStop() != null && c.consecutiveLosses() >= stops.consecutiveLossStop()) {
            return fail("CONSECUTIVE_LOSS_STOP", c.consecutiveLosses() + " consecutive losses reached the stop");
        }
        if (stops.consecutiveWinStop() != null && c.consecutiveWins() >= stops.consecutiveWinStop()) {
            return fail("CONSECUTIVE_WIN_STOP", c.consecutiveWins() + " consecutive wins reached the stop");
        }
        return ok();
    }

    private static RiskRule rule(String name, Function<PreTradeContext, Optional<RiskViolation>> check) {
        return new RiskRule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Optional<RiskViolation> check(PreTradeContext context) {
                return check.apply(context).map(v -> v.rule() == null || v.rule().isEmpty()
                        ? new RiskViolation(name, v.code(), v.message(), v.requiresReconfirmation()) : v);
            }
        };
    }

    private static Optional<RiskViolation> ok() {
        return Optional.empty();
    }

    private static Optional<RiskViolation> fail(String code, String message) {
        return Optional.of(new RiskViolation("", code, message, false));
    }
}
