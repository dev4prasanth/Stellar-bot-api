package com.stellartrack.betfair.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Raw Betfair Exchange Betting API (API-NG, SportsAPING/v1.0) wire types, named after the official
 * type definitions. Provider enumerations are kept as strings so a new provider value never breaks
 * deserialization; adapters translate them into application models.
 */
public final class BettingTypes {

    private BettingTypes() {
    }

    // ---- Requests -----------------------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record TimeRange(Instant from, Instant to) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record MarketFilter(
            Set<String> eventTypeIds,
            Set<String> eventIds,
            Set<String> marketIds,
            Set<String> marketCountries,
            Set<String> marketTypeCodes,
            Set<String> venues,
            TimeRange marketStartTime,
            Boolean inPlayOnly) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record PriceProjection(Set<String> priceData, Boolean virtualise) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LimitOrder(BigDecimal size, BigDecimal price, String persistenceType) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlaceInstruction(String orderType, Long selectionId, BigDecimal handicap, String side,
            LimitOrder limitOrder, String customerOrderRef) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketVersion(Long version) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CancelInstruction(String betId, BigDecimal sizeReduction) {
    }

    // ---- Responses ----------------------------------------------------------------------------------

    public record EventType(String id, String name) {
    }

    public record Event(String id, String name, String countryCode, String timezone, String venue, Instant openDate) {
    }

    public record EventResult(Event event, Integer marketCount) {
    }

    public record MarketDescription(Boolean persistenceEnabled, Boolean bspMarket, Instant marketTime,
            Instant suspendTime, String bettingType, Boolean turnInPlayEnabled, String marketType, String regulator,
            BigDecimal marketBaseRate, Boolean discountAllowed, String wallet, String raceType, BigDecimal eachWayDivisor) {
    }

    public record RunnerCatalog(Long selectionId, String runnerName, BigDecimal handicap, Integer sortPriority,
            Map<String, String> metadata) {
    }

    public record MarketCatalogue(String marketId, String marketName, Instant marketStartTime,
            MarketDescription description, BigDecimal totalMatched, List<RunnerCatalog> runners, EventType eventType,
            Event event) {
    }

    public record PriceSize(BigDecimal price, BigDecimal size) {
    }

    public record ExchangePrices(List<PriceSize> availableToBack, List<PriceSize> availableToLay,
            List<PriceSize> tradedVolume) {
    }

    public record Runner(Long selectionId, BigDecimal handicap, String status, BigDecimal adjustmentFactor,
            BigDecimal lastPriceTraded, BigDecimal totalMatched, Instant removalDate, ExchangePrices ex) {
    }

    public record MarketBook(String marketId, Boolean isMarketDataDelayed, String status, Integer betDelay,
            Boolean bspReconciled, Boolean complete, Boolean inplay, Integer numberOfWinners, Integer numberOfRunners,
            Integer numberOfActiveRunners, Instant lastMatchTime, BigDecimal totalMatched, BigDecimal totalAvailable,
            Boolean crossMatching, Boolean runnersVoidable, Long version, List<Runner> runners) {
    }

    public record PlaceInstructionReport(String status, String errorCode, String orderStatus,
            PlaceInstruction instruction, String betId, Instant placedDate, BigDecimal averagePriceMatched,
            BigDecimal sizeMatched) {
    }

    public record PlaceExecutionReport(String customerRef, String status, String errorCode, String marketId,
            List<PlaceInstructionReport> instructionReports) {
    }

    public record CancelInstructionReport(String status, String errorCode, CancelInstruction instruction,
            BigDecimal sizeCancelled, Instant cancelledDate) {
    }

    public record CancelExecutionReport(String customerRef, String status, String errorCode, String marketId,
            List<CancelInstructionReport> instructionReports) {
    }

    public record CurrentOrderSummary(String betId, String marketId, Long selectionId, BigDecimal handicap,
            PriceSize priceSize, BigDecimal bspLiability, String side, String status, String persistenceType,
            String orderType, Instant placedDate, Instant matchedDate, BigDecimal averagePriceMatched,
            BigDecimal sizeMatched, BigDecimal sizeRemaining, BigDecimal sizeLapsed, BigDecimal sizeCancelled,
            BigDecimal sizeVoided, String customerOrderRef, String customerStrategyRef) {
    }

    public record CurrentOrderSummaryReport(List<CurrentOrderSummary> currentOrders, Boolean moreAvailable) {
    }

    public record ClearedOrderSummary(String eventTypeId, String eventId, String marketId, Long selectionId,
            BigDecimal handicap, String betId, Instant placedDate, String persistenceType, String orderType,
            String side, String betOutcome, BigDecimal priceRequested, Instant settledDate, Instant lastMatchedDate,
            Integer betCount, BigDecimal commission, BigDecimal priceMatched, Boolean priceReduced,
            BigDecimal sizeSettled, BigDecimal profit, BigDecimal sizeCancelled, String customerOrderRef,
            String customerStrategyRef) {
    }

    public record ClearedOrderSummaryReport(List<ClearedOrderSummary> clearedOrders, Boolean moreAvailable) {
    }
}
