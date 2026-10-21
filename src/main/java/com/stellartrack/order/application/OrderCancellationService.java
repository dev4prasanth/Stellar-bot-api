package com.stellartrack.order.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditOutcome;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.common.events.UiNotification;
import com.stellartrack.common.exception.BusinessRuleException;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.order.domain.OrderIntentStatus;
import com.stellartrack.order.domain.Revocability;
import com.stellartrack.order.persistence.BetOrderEntity;
import com.stellartrack.order.persistence.BetOrderRepository;
import com.stellartrack.order.persistence.CancellationRequestEntity;
import com.stellartrack.order.persistence.CancellationRequestRepository;
import com.stellartrack.order.port.CancellationResult;
import com.stellartrack.order.port.OrderExecutionPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Cancels the unmatched portion of an order (fully, or reduced by {@code sizeReduction}). */
@Service
public class OrderCancellationService {

    private final BetOrderRepository betOrders;
    private final CancellationRequestRepository cancellations;
    private final OrderExecutionPort execution;
    private final OrderReconciliationService reconciliation;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OrderCancellationService(BetOrderRepository betOrders, CancellationRequestRepository cancellations,
            OrderExecutionPort execution, OrderReconciliationService reconciliation, AuditTrail auditTrail,
            ApplicationEventPublisher events, Clock clock, PlatformTransactionManager txManager) {
        this.betOrders = betOrders;
        this.cancellations = cancellations;
        this.execution = execution;
        this.reconciliation = reconciliation;
        this.auditTrail = auditTrail;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Revokes the bet's unmatched stake. Its latest status is first re-read from Betfair, so a bet that matched,
     * lapsed or was cancelled since the screen was drawn is refused instead of sent; if Betfair cannot be reached the
     * request is refused (nothing is cancelled on a status that could not be confirmed).
     */
    public CancellationResult cancel(String betId, BigDecimal sizeReduction, String actor) {
        betOrders.findById(betId).orElseThrow(() -> new NotFoundException("Order", betId));
        try {
            reconciliation.refreshBet(betId);
        } catch (RuntimeException e) {
            throw new BusinessRuleException("BET_STATUS_UNVERIFIED",
                    "The bet's latest status could not be confirmed with Betfair, so nothing was cancelled. Try again.");
        }
        BetOrderEntity order = betOrders.findById(betId).orElseThrow(() -> new NotFoundException("Order", betId));
        Revocability revocability = Revocability.ofOrder(OrderIntentStatus.PLACED.name(), order.getStatus(),
                order.getSizeMatched(), order.getSizeRemaining(), order.getSizeCancelled(), order.getSizeLapsed(),
                order.getSizeVoided(), null);
        if (!revocability.revocable()) {
            throw new BusinessRuleException("NOTHING_TO_CANCEL", revocability.reason());
        }
        if (sizeReduction != null && (sizeReduction.signum() <= 0 || sizeReduction.compareTo(order.getSizeRemaining()) >= 0)) {
            throw new BusinessRuleException("INVALID_SIZE_REDUCTION",
                    "Size reduction must be positive and less than the unmatched amount; omit it to cancel everything");
        }
        UUID requestId = tx.execute(s -> {
            CancellationRequestEntity request = cancellations.save(
                    new CancellationRequestEntity(betId, sizeReduction, actor, clock.instant()));
            auditTrail.record(AuditRecord.admin(actor, AuditAction.ORDER_CANCEL_REQUESTED).entity("bet_order", betId));
            return request.getId();
        });
        CancellationResult result = execution.cancel(order.getMarketId(), betId, sizeReduction, requestId.toString().replace("-", ""));
        tx.executeWithoutResult(s -> {
            cancellations.findById(requestId).orElseThrow().complete(result, clock.instant());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("outcome", result.outcome().name());
            if (result.errorCode() != null) {
                details.put("errorCode", result.errorCode());
            }
            auditTrail.record(AuditRecord.admin(actor, AuditAction.ORDER_CANCEL_COMPLETED).entity("bet_order", betId)
                    .outcome(result.outcome() == CancellationResult.Outcome.SUCCESS ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
                    .details(details));
        });
        // Whatever happened, refresh the order from Betfair so quantities reflect the provider state.
        try {
            reconciliation.refreshBet(betId);
        } catch (RuntimeException ignored) {
            // the scheduled reconciliation will retry
        }
        events.publishEvent(UiNotification.of("orders", betId, Map.of("cancellation", result.outcome().name())));
        return result;
    }
}
