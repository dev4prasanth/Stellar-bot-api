package com.stellartrack.configuration.domain;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.market.filter.RunnerFilterCriteria;
import com.stellartrack.order.port.PersistenceType;
import com.stellartrack.staking.domain.StakingSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Versioned settings document of a named configuration (schema version {@link #SCHEMA_VERSION}).
 * Definitions (scope, reset and denominators) are documented in docs/reporting/business-rules.md:
 * <ul>
 *   <li>race = one Betfair event's race (WIN and PLACE markets of the same race share the limit);</li>
 *   <li>session = one automation run, from Start to Stop;</li>
 *   <li>daily limits reset at midnight in the reporting time zone;</li>
 *   <li>P/L stops use current settlement revisions of this configuration's settled entries in the run;
 *       voided bets count as neither win nor loss; percentages use the bank captured at run start.</li>
 * </ul>
 */
public record BotConfigurationSettings(
        @NotNull @Valid Selection selection,
        @NotNull @Valid Market market,
        @Valid RunnerFilterCriteria filters,
        @NotNull @Valid StakingSettings staking,
        @NotNull @Valid Timing timing,
        @NotNull @Valid Price price,
        @NotNull @Valid Limits limits,
        @NotNull @Valid Stops stops,
        @Valid CapitalAllocation capital,
        @NotNull @Valid Trading trading,
        @NotNull @Valid Approval approval) {

    public static final int SCHEMA_VERSION = 1;

    public enum SelectionSourceType {
        /** Runners explicitly chosen by the administrator in Manual Mode. */
        ADMIN_SELECTED,
        /** Runners passing the configuration's explicit market/runner filters. */
        MARKET_CRITERIA,
        /** Approved prediction strategy over validated Layer 1/2/3 inputs (blocked until approved). */
        PREDICTION
    }

    public record Selection(@NotNull SelectionSourceType source, @Size(max = 60) String strategyCode,
            @Min(1) @Max(20) Integer maxSelectionsPerRace) {

        @AssertTrue(message = "PREDICTION selection requires a strategyCode")
        public boolean isStrategyPresent() {
            return source != SelectionSourceType.PREDICTION || (strategyCode != null && !strategyCode.isBlank());
        }
    }

    public record Market(@NotNull MarketType marketType, @NotNull BetSide side, Set<String> countries, Set<String> venues) {
    }

    /**
     * Entry window is relative to the scheduled start (e.g. from 30 to 2 minutes before the off).
     * {@code maxInPlayBetDelaySeconds}: in-play entries are refused when Betfair's in-play bet delay for the market
     * exceeds this value (the delay Betfair applies before an in-play bet is accepted).
     */
    public record Timing(@Min(0) @Max(1440) int entryWindowStartMinutesBefore,
            @Min(0) @Max(1440) int entryWindowEndMinutesBefore,
            boolean allowInPlay,
            @Min(0) @Max(60) int maxInPlayBetDelaySeconds,
            @NotNull PersistenceType persistenceType) {

        @AssertTrue(message = "entry window start must be at or before the window end")
        public boolean isWindowOrdered() {
            return entryWindowStartMinutesBefore >= entryWindowEndMinutesBefore;
        }
    }

    /**
     * @param maxPriceMoveTicks a manual ticket must be re-confirmed if the price moves more than this many ticks
     */
    public record Price(@DecimalMin("1.01") @DecimalMax("1000") BigDecimal minOdds,
            @DecimalMin("1.01") @DecimalMax("1000") BigDecimal maxOdds,
            @Min(0) @Max(50) int maxPriceMoveTicks) {

        @AssertTrue(message = "minOdds must not exceed maxOdds")
        public boolean isOrdered() {
            return minOdds == null || maxOdds == null || minOdds.compareTo(maxOdds) <= 0;
        }
    }

    public record Limits(@Min(1) @Max(20) int maxEntriesPerRace,
            @Min(1) @Max(10_000) int maxEntriesPerDay,
            @Min(1) @Max(10_000) int maxEntriesPerSession,
            @DecimalMin("0.01") BigDecimal maxExposurePerRace,
            @DecimalMin("0.01") BigDecimal maxTotalOpenExposure,
            @DecimalMin("0.00") BigDecimal minAvailableBalance) {
    }

    public record Stops(@DecimalMin("0.01") BigDecimal stopProfitAmount,
            @DecimalMin("0.01") BigDecimal stopLossAmount,
            @DecimalMin("0.01") @DecimalMax("1000") BigDecimal stopProfitPercent,
            @DecimalMin("0.01") @DecimalMax("100") BigDecimal stopLossPercent,
            @Min(1) @Max(100) Integer consecutiveLossStop,
            @Min(1) @Max(100) Integer consecutiveWinStop) {
    }

    /** Internal limit on the shared account funds; never a separate wallet. */
    public record CapitalAllocation(@DecimalMin("0.01") BigDecimal allocatedAmount) {
    }

    /**
     * Advanced trading features. Each stays false until its formula, trigger, linkage and failure policy are
     * approved (see Capability DUTCHING, EQUALISATION, CLOSE_OUT, PRICE_MOVEMENT_DETECTION, FREE_BET_POSITIONING).
     */
    public record Trading(boolean dutching, boolean priceMovementDetection, boolean equalisation, boolean closeOut,
            boolean freeBetPositioning) {

        public static Trading none() {
            return new Trading(false, false, false, false, false);
        }

        public boolean anyEnabled() {
            return dutching || priceMovementDetection || equalisation || closeOut || freeBetPositioning;
        }
    }

    /**
     * @param manualApprovalRequired superseded by the global trading mode (MANUAL/AUTOMATIC) and ignored; kept so
     *                               stored, immutable configuration versions still deserialize
     * @param approvalTimeoutSeconds how long a proposal waits for approval in MANUAL trading mode before it expires
     */
    public record Approval(boolean manualApprovalRequired, @Min(10) @Max(3600) int approvalTimeoutSeconds) {
    }
}
