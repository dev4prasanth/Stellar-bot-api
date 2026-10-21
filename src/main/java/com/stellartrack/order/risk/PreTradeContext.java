package com.stellartrack.order.risk;

import com.stellartrack.capital.application.CapitalService.FundsView;
import com.stellartrack.common.api.Freshness;
import com.stellartrack.configuration.domain.BotConfigurationSettings;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.order.application.PlacementCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Everything the risk rules evaluate, gathered once from authoritative sources immediately before submission.
 * Rules are pure functions of this context, which makes them independently testable.
 *
 * @param watchModeActive the global trading mode is WATCH: no real entry may be submitted
 * @param simulated       the context describes a simulated (Watch) bet built from Watch records and the virtual
 *                        capital, never a real submission; delayed market data is acceptable for it
 */
public record PreTradeContext(
        PlacementCommand command,
        Instant now,
        boolean exchangeAvailable,
        boolean stopAllEngaged,
        MarketSummary market,
        MarketPrices prices,
        Freshness priceFreshness,
        Optional<FundsView> funds,
        boolean duplicateLiveEntry,
        BigDecimal configurationOpenExposure,
        BigDecimal configurationRaceExposure,
        int configurationRaceEntries,
        int configurationDayEntries,
        int configurationSessionEntries,
        BigDecimal sessionSettledProfit,
        int consecutiveLosses,
        int consecutiveWins,
        boolean watchModeActive,
        boolean simulated) {

    public Optional<BotConfigurationSettings> settings() {
        return command.configuration() == null ? Optional.empty() : Optional.of(command.configuration().settings());
    }

    public Optional<MarketPrices.RunnerPrices> runner() {
        return prices == null ? Optional.empty() : prices.runner(command.selectionId());
    }

    public BigDecimal newExposure() {
        return command.stake() == null || command.stake().exposure() == null ? BigDecimal.ZERO : command.stake().exposure();
    }

    public boolean isAutomatic() {
        return command.mode() == com.stellartrack.common.domain.OperatingMode.AUTOMATIC;
    }
}
