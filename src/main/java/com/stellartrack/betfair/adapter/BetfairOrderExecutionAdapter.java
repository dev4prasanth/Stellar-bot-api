package com.stellartrack.betfair.adapter;

import com.stellartrack.betfair.api.BettingTypes.CancelExecutionReport;
import com.stellartrack.betfair.api.BettingTypes.CancelInstruction;
import com.stellartrack.betfair.api.BettingTypes.CancelInstructionReport;
import com.stellartrack.betfair.api.BettingTypes.ClearedOrderSummary;
import com.stellartrack.betfair.api.BettingTypes.ClearedOrderSummaryReport;
import com.stellartrack.betfair.api.BettingTypes.CurrentOrderSummary;
import com.stellartrack.betfair.api.BettingTypes.CurrentOrderSummaryReport;
import com.stellartrack.betfair.api.BettingTypes.LimitOrder;
import com.stellartrack.betfair.api.BettingTypes.PlaceExecutionReport;
import com.stellartrack.betfair.api.BettingTypes.PlaceInstruction;
import com.stellartrack.betfair.api.BettingTypes.PlaceInstructionReport;
import com.stellartrack.betfair.api.BettingTypes.TimeRange;
import com.stellartrack.betfair.client.BetfairBettingClient;
import com.stellartrack.betfair.error.BetfairApiException;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.order.port.CancellationResult;
import com.stellartrack.order.port.OrderExecutionPort;
import com.stellartrack.order.port.PlaceOrderCommand;
import com.stellartrack.order.port.PlacementResult;
import com.stellartrack.order.port.ProviderMarketSettlement;
import com.stellartrack.order.port.ProviderOrderState;
import com.stellartrack.order.port.ProviderSettlement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Betfair implementation of {@link OrderExecutionPort}, preserving provider identifiers, statuses and
 * instruction-level error codes. Outcome rules:
 * <ul>
 *   <li>Report status SUCCESS with instruction SUCCESS → PLACED.</li>
 *   <li>Report/instruction FAILURE → EXCHANGE_REJECTED (Betfair refused it; nothing was placed).</li>
 *   <li>Report status TIMEOUT, transport timeout/reset, or APING TIMEOUT_ERROR/UNEXPECTED_ERROR → UNKNOWN_OUTCOME.</li>
 *   <li>Request rejected before execution (session, access, input, throttling) → TECHNICAL_FAILURE.</li>
 * </ul>
 */
@Component
public class BetfairOrderExecutionAdapter implements OrderExecutionPort {

    private static final Set<String> AMBIGUOUS_APING_CODES = Set.of("TIMEOUT_ERROR", "UNEXPECTED_ERROR");

    private final BetfairBettingClient client;

    public BetfairOrderExecutionAdapter(BetfairBettingClient client) {
        this.client = client;
    }

    @Override
    public PlacementResult place(PlaceOrderCommand command) {
        PlaceInstruction instruction = new PlaceInstruction("LIMIT", command.selectionId(), command.handicap(),
                command.side().name(),
                new LimitOrder(command.size(), command.price(), command.persistenceType().name()),
                command.customerOrderRef());
        PlaceExecutionReport report;
        try {
            report = client.placeOrders(command.marketId(), List.of(instruction), command.customerRef(),
                    command.marketVersion(), command.customerStrategyRef());
        } catch (BetfairApiException e) {
            if (e.ambiguousForWrite() || AMBIGUOUS_APING_CODES.contains(e.providerErrorCode())) {
                return PlacementResult.unknown(e.getMessage());
            }
            return PlacementResult.technicalFailure(e.providerErrorCode() != null ? e.providerErrorCode() : e.kind().name(),
                    e.getMessage());
        }
        return toPlacementResult(report);
    }

    static PlacementResult toPlacementResult(PlaceExecutionReport report) {
        PlaceInstructionReport ir = report.instructionReports() == null || report.instructionReports().isEmpty()
                ? null : report.instructionReports().getFirst();
        String status = report.status();
        if ("TIMEOUT".equals(status) || (ir != null && "TIMEOUT".equals(ir.status()))) {
            return new PlacementResult(PlacementResult.Outcome.UNKNOWN_OUTCOME, status, report.errorCode(),
                    ir == null ? null : ir.status(), ir == null ? null : ir.errorCode(), ir == null ? null : ir.betId(),
                    null, null, null, null, "Betfair reported TIMEOUT; order state must be reconciled");
        }
        if (ir != null && "SUCCESS".equals(ir.status())) {
            return new PlacementResult(PlacementResult.Outcome.PLACED, status, report.errorCode(), ir.status(),
                    ir.errorCode(), ir.betId(), ir.placedDate(), ir.averagePriceMatched(), ir.sizeMatched(),
                    ir.orderStatus(), null);
        }
        return new PlacementResult(PlacementResult.Outcome.EXCHANGE_REJECTED, status, report.errorCode(),
                ir == null ? null : ir.status(), ir == null ? null : ir.errorCode(), null, null, null, null, null,
                "Betfair rejected the order");
    }

    @Override
    public CancellationResult cancel(String marketId, String betId, BigDecimal sizeReduction, String customerRef) {
        CancelExecutionReport report;
        try {
            report = client.cancelOrders(marketId, List.of(new CancelInstruction(betId, sizeReduction)), customerRef);
        } catch (BetfairApiException e) {
            CancellationResult.Outcome outcome = e.ambiguousForWrite() || AMBIGUOUS_APING_CODES.contains(e.providerErrorCode())
                    ? CancellationResult.Outcome.UNKNOWN_OUTCOME : CancellationResult.Outcome.FAILURE;
            return new CancellationResult(outcome, null, e.providerErrorCode() != null ? e.providerErrorCode() : e.kind().name(), null);
        }
        CancelInstructionReport ir = report.instructionReports() == null || report.instructionReports().isEmpty()
                ? null : report.instructionReports().getFirst();
        if ("TIMEOUT".equals(report.status())) {
            return new CancellationResult(CancellationResult.Outcome.UNKNOWN_OUTCOME, null, "TIMEOUT", null);
        }
        if (ir != null && "SUCCESS".equals(ir.status())) {
            return new CancellationResult(CancellationResult.Outcome.SUCCESS, ir.sizeCancelled(), null, ir.cancelledDate());
        }
        String error = ir != null && ir.errorCode() != null ? ir.errorCode() : report.errorCode();
        return new CancellationResult(CancellationResult.Outcome.FAILURE, null, error, null);
    }

    @Override
    public List<ProviderOrderState> currentOrders(Set<String> betIds, Set<String> marketIds, Set<String> customerOrderRefs) {
        List<ProviderOrderState> result = new ArrayList<>();
        int from = 0;
        while (true) {
            CurrentOrderSummaryReport page = client.listCurrentOrders(betIds, marketIds, customerOrderRefs, from);
            List<CurrentOrderSummary> orders = page.currentOrders() == null ? List.of() : page.currentOrders();
            orders.forEach(o -> result.add(toState(o)));
            if (!Boolean.TRUE.equals(page.moreAvailable()) || orders.isEmpty()) {
                return result;
            }
            from += orders.size();
        }
    }

    @Override
    public List<ProviderSettlement> clearedOrders(String betStatus, Instant settledFrom, Instant settledTo, Set<String> betIds) {
        List<ProviderSettlement> result = new ArrayList<>();
        forEachClearedPage(betStatus, settledFrom, settledTo, betIds, null, o -> result.add(new ProviderSettlement(
                o.betId(), o.marketId(), o.selectionId() == null ? 0 : o.selectionId(), side(o.side()), betStatus,
                o.betOutcome(), o.settledDate(), o.priceRequested(), o.priceMatched(), o.priceReduced(), o.sizeSettled(),
                o.sizeCancelled(), o.profit(), o.lastMatchedDate(), o.placedDate(), o.customerOrderRef(),
                o.customerStrategyRef())));
        return result;
    }

    @Override
    public List<ProviderMarketSettlement> clearedMarkets(Instant settledFrom, Instant settledTo) {
        List<ProviderMarketSettlement> result = new ArrayList<>();
        forEachClearedPage("SETTLED", settledFrom, settledTo, null, "MARKET", o -> result.add(
                new ProviderMarketSettlement(o.marketId(), o.settledDate(), o.profit(), o.commission())));
        return result;
    }

    private void forEachClearedPage(String betStatus, Instant from, Instant to, Set<String> betIds, String groupBy,
            java.util.function.Consumer<ClearedOrderSummary> consumer) {
        int fromRecord = 0;
        while (true) {
            ClearedOrderSummaryReport page = client.listClearedOrders(betStatus, new TimeRange(from, to), betIds, groupBy,
                    fromRecord);
            List<ClearedOrderSummary> orders = page.clearedOrders() == null ? List.of() : page.clearedOrders();
            orders.forEach(consumer);
            if (!Boolean.TRUE.equals(page.moreAvailable()) || orders.isEmpty()) {
                return;
            }
            fromRecord += orders.size();
        }
    }

    private static ProviderOrderState toState(CurrentOrderSummary o) {
        return new ProviderOrderState(o.betId(), o.marketId(), o.selectionId() == null ? 0 : o.selectionId(),
                o.handicap(), side(o.side()),
                o.priceSize() == null ? null : o.priceSize().price(),
                o.priceSize() == null ? null : o.priceSize().size(),
                o.averagePriceMatched(), o.sizeMatched(), o.sizeRemaining(), o.sizeLapsed(), o.sizeCancelled(),
                o.sizeVoided(), "EXECUTION_COMPLETE".equals(o.status()), o.placedDate(), o.matchedDate(),
                o.persistenceType(), o.orderType(), o.customerOrderRef(), o.customerStrategyRef(),
                ProviderOrderState.Source.POLL);
    }

    private static BetSide side(String side) {
        return side == null ? null : BetSide.valueOf(side);
    }
}
