package com.stellartrack.order.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditOutcome;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.common.provider.ExchangeAvailability;
import com.stellartrack.order.domain.OrderIntentStatus;
import com.stellartrack.order.persistence.BetOrderRepository;
import com.stellartrack.order.persistence.OrderIntentEntity;
import com.stellartrack.order.persistence.OrderIntentRepository;
import com.stellartrack.order.persistence.ReconciliationCheckpointEntity;
import com.stellartrack.order.persistence.ReconciliationCheckpointRepository;
import com.stellartrack.order.port.OrderExecutionPort;
import com.stellartrack.order.port.ProviderOrderState;
import com.stellartrack.order.port.ProviderSettlement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background reconciliation with Betfair-authoritative records:
 * <ul>
 *   <li>Ambiguous submissions (UNKNOWN_OUTCOME, or SUBMITTING interrupted by a crash) are looked up by their
 *       customerOrderRef. Found → PLACED with the real betId. Not found after the grace period → marked
 *       NOT_FOUND_AFTER_RECONCILIATION (still re-checkable). They are never resubmitted automatically.</li>
 *   <li>Open orders are polled with listCurrentOrders (complementing the order stream).</li>
 *   <li>Cleared bets (SETTLED, VOIDED, LAPSED, CANCELLED) and market-level commission are synchronized.</li>
 * </ul>
 */
@Service
public class OrderReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationService.class);
    private static final Duration UNKNOWN_GRACE = Duration.ofMinutes(10);
    private static final List<String> CLEARED_STATUSES = List.of("SETTLED", "VOIDED", "LAPSED", "CANCELLED");

    private final OrderExecutionPort execution;
    private final OrderIntentRepository intents;
    private final BetOrderRepository betOrders;
    private final ReconciliationCheckpointRepository checkpoints;
    private final OrderStateApplier applier;
    private final SettlementRecorder settlementRecorder;
    private final ExchangeAvailability exchange;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final Duration orderTimeout;
    private final Duration clearedLookback;
    private final TransactionTemplate tx;

    public OrderReconciliationService(OrderExecutionPort execution, OrderIntentRepository intents,
            BetOrderRepository betOrders, ReconciliationCheckpointRepository checkpoints, OrderStateApplier applier,
            SettlementRecorder settlementRecorder, ExchangeAvailability exchange, AuditTrail auditTrail, Clock clock,
            @Value("${stellartrack.betfair.order-timeout}") Duration orderTimeout,
            @Value("${stellartrack.reconciliation.cleared-orders-lookback}") Duration clearedLookback,
            PlatformTransactionManager txManager) {
        this.execution = execution;
        this.intents = intents;
        this.betOrders = betOrders;
        this.checkpoints = checkpoints;
        this.applier = applier;
        this.settlementRecorder = settlementRecorder;
        this.exchange = exchange;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.orderTimeout = orderTimeout;
        this.clearedLookback = clearedLookback;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${stellartrack.reconciliation.current-orders-interval}", initialDelay = 30_000)
    public void reconcileOpenAndAmbiguous() {
        if (!exchange.isAvailable()) {
            return;
        }
        runCheckpointed("ambiguous-submissions", this::reconcileAmbiguous);
        runCheckpointed("current-orders", this::syncCurrentOrders);
    }

    @Scheduled(fixedDelayString = "${stellartrack.reconciliation.cleared-orders-interval}", initialDelay = 90_000)
    public void reconcileCleared() {
        if (!exchange.isAvailable()) {
            return;
        }
        runCheckpointed("cleared-orders", this::syncClearedOrders);
    }

    int reconcileAmbiguous() {
        Instant now = clock.instant();
        List<OrderIntentEntity> candidates = new java.util.ArrayList<>(intents.findByStatus(OrderIntentStatus.UNKNOWN_OUTCOME));
        // A crash between "submitting" and recording the outcome leaves the intent in SUBMITTING.
        candidates.addAll(intents.findByStatusAndUpdatedAtBefore(OrderIntentStatus.SUBMITTING,
                now.minus(orderTimeout).minusSeconds(30)));
        candidates.addAll(intents.findByStatusAndUpdatedAtBefore(OrderIntentStatus.NOT_FOUND_AFTER_RECONCILIATION,
                now.minus(Duration.ofMinutes(1))).stream().filter(i -> i.getCreatedAt().isAfter(now.minus(Duration.ofDays(1)))).toList());
        int resolved = 0;
        for (OrderIntentEntity intent : candidates) {
            List<ProviderOrderState> found = execution.currentOrders(Set.of(), Set.of(), Set.of(intent.getCustomerOrderRef()));
            Optional<ProviderOrderState> match = found.stream()
                    .filter(o -> intent.getCustomerOrderRef().equals(o.customerOrderRef())).findFirst();
            if (match.isPresent()) {
                resolvePlaced(intent, match.get());
                resolved++;
            } else if (intent.getStatus() == OrderIntentStatus.SUBMITTING) {
                transition(intent, OrderIntentStatus.UNKNOWN_OUTCOME, "Interrupted submission; awaiting reconciliation");
            } else if (intent.getStatus() == OrderIntentStatus.UNKNOWN_OUTCOME
                    && intent.getUpdatedAt().isBefore(now.minus(UNKNOWN_GRACE))) {
                transition(intent, OrderIntentStatus.NOT_FOUND_AFTER_RECONCILIATION,
                        "No order with this reference found at Betfair after " + UNKNOWN_GRACE.toMinutes() + " minutes");
            }
        }
        return resolved;
    }

    private void resolvePlaced(OrderIntentEntity intent, ProviderOrderState state) {
        tx.executeWithoutResult(status -> {
            OrderIntentEntity fresh = intents.findById(intent.getId()).orElseThrow();
            if (fresh.getStatus() == OrderIntentStatus.SUBMITTING) {
                fresh.transition(OrderIntentStatus.UNKNOWN_OUTCOME, "Interrupted submission", clock.instant());
            }
            fresh.transition(OrderIntentStatus.PLACED, "Found at Betfair during reconciliation (betId " + state.betId() + ")",
                    clock.instant());
            applier.apply(state);
            auditTrail.record(AuditRecord.system(AuditAction.ORDER_RECONCILED).entity("order_intent", fresh.getId())
                    .configuration(fresh.getConfigurationId(), fresh.getConfigurationVersion())
                    .details(Map.of("betId", state.betId())));
        });
    }

    private void transition(OrderIntentEntity intent, OrderIntentStatus target, String reason) {
        tx.executeWithoutResult(status -> {
            OrderIntentEntity fresh = intents.findById(intent.getId()).orElseThrow();
            if (fresh.getStatus() != target) {
                fresh.transition(target, reason, clock.instant());
                auditTrail.record(AuditRecord.system(AuditAction.ORDER_RECONCILED).entity("order_intent", fresh.getId())
                        .outcome(AuditOutcome.INFO).details(Map.of("status", target.name())));
            }
        });
    }

    int syncCurrentOrders() {
        Set<String> markets = new HashSet<>(betOrders.findMarketsWithExecutableOrders());
        if (markets.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (ProviderOrderState state : execution.currentOrders(Set.of(), markets, Set.of())) {
            if (applier.apply(state)) {
                changed++;
            }
        }
        return changed;
    }

    int syncClearedOrders() {
        Instant to = clock.instant();
        Instant from = to.minus(clearedLookback);
        Set<String> knownBets = new HashSet<>(betOrders.findUnsettledBetIds());
        int stored = 0;
        for (String status : CLEARED_STATUSES) {
            for (ProviderSettlement settlement : execution.clearedOrders(status, from, to, Set.of())) {
                if (knownBets.contains(settlement.betId()) || betOrders.existsById(settlement.betId())) {
                    stored += settlementRecorder.record(settlement) ? 1 : 0;
                }
            }
        }
        Set<String> ourMarkets = new HashSet<>(betOrders.findMarketIdsWithBetsPlacedSince(from.minus(clearedLookback)));
        execution.clearedMarkets(from, to).stream()
                .filter(m -> ourMarkets.contains(m.marketId()))
                .forEach(settlementRecorder::recordMarket);
        return stored;
    }

    private void runCheckpointed(String name, Supplier<Integer> job) {
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> checkpoint(name).attempted(now));
        try {
            int result = job.get();
            tx.executeWithoutResult(s -> checkpoint(name).succeeded(clock.instant()));
            if (result > 0) {
                log.info("Reconciliation {} updated {} records", name, result);
            }
        } catch (RuntimeException e) {
            log.warn("Reconciliation {} failed: {}", name, e.getMessage());
            tx.executeWithoutResult(s -> checkpoint(name).failed(e.getMessage()));
        }
    }

    private ReconciliationCheckpointEntity checkpoint(String name) {
        return checkpoints.findById(name).orElseGet(() -> checkpoints.save(new ReconciliationCheckpointEntity(name)));
    }

    /** Explicit reconciliation of one bet (e.g. after a cancellation with an unknown outcome). */
    public void refreshBet(String betId) {
        execution.currentOrders(Set.of(betId), Set.of(), Set.of()).forEach(applier::apply);
    }

    public List<ReconciliationCheckpointEntity> checkpoints() {
        return checkpoints.findAll();
    }
}
