package com.stellartrack.order.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditOutcome;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.common.events.UiNotification;
import com.stellartrack.order.persistence.BetOrderRepository;
import com.stellartrack.order.persistence.OrderIntentEntity;
import com.stellartrack.order.persistence.OrderIntentRepository;
import com.stellartrack.order.persistence.SubmissionAttemptEntity;
import com.stellartrack.order.port.OrderExecutionPort;
import com.stellartrack.order.port.PlaceOrderCommand;
import com.stellartrack.order.port.PlacementResult;
import com.stellartrack.order.risk.PreTradeContext;
import com.stellartrack.order.risk.PreTradeValidator;
import com.stellartrack.order.risk.RiskViolation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The single path to real-money order submission, shared by Manual Mode, approved proposals and Automatic Mode:
 * <ol>
 *   <li>Idempotency: a repeated request key returns the original outcome without resubmitting.</li>
 *   <li>Pre-trade revalidation of authorization-independent business rules against fresh provider state.</li>
 *   <li>Durable order intention with provider references (unique live-entry constraint prevents duplicates).</li>
 *   <li>Submission to Betfair with the market version (rejects if the market changed, e.g. a non-runner).</li>
 *   <li>Durable outcome; ambiguous results become UNKNOWN_OUTCOME and are reconciled, never blindly retried.</li>
 * </ol>
 */
@Service
public class OrderPlacementService {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacementService.class);

    private final PreTradeContextFactory contextFactory;
    private final PreTradeValidator validator;
    private final OrderIntentStore store;
    private final OrderIntentRepository intents;
    private final BetOrderRepository betOrders;
    private final OrderExecutionPort execution;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final String customerStrategyRef;

    public OrderPlacementService(PreTradeContextFactory contextFactory, PreTradeValidator validator,
            OrderIntentStore store, OrderIntentRepository intents, BetOrderRepository betOrders,
            OrderExecutionPort execution, AuditTrail auditTrail, ApplicationEventPublisher events,
            @Value("${stellartrack.betfair.customer-strategy-ref}") String customerStrategyRef) {
        this.contextFactory = contextFactory;
        this.validator = validator;
        this.store = store;
        this.intents = intents;
        this.betOrders = betOrders;
        this.execution = execution;
        this.auditTrail = auditTrail;
        this.events = events;
        this.customerStrategyRef = customerStrategyRef;
    }

    /** Evaluates every pre-trade rule without submitting (used for ticket review). */
    public List<RiskViolation> preview(PlacementCommand command) {
        return validator.validate(contextFactory.build(command));
    }

    public PlacementOutcome submit(PlacementCommand command) {
        Optional<OrderIntentEntity> existing = intents.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return describe(existing.get(), "Duplicate request: returning the original submission");
        }
        PreTradeContext context = contextFactory.build(command);
        List<RiskViolation> violations = validator.validate(context);
        if (!violations.isEmpty()) {
            auditRiskRejection(command, violations);
            return PlacementOutcome.riskRejected(violations);
        }
        OrderIntentEntity intent;
        try {
            intent = store.createIntent(command, context.market().marketType());
        } catch (DataIntegrityViolationException e) {
            List<RiskViolation> duplicate = List.of(RiskViolation.of("DUPLICATE_ENTRY", "DUPLICATE_ENTRY",
                    "A concurrent request already holds this entry or idempotency key"));
            auditRiskRejection(command, duplicate);
            return PlacementOutcome.riskRejected(duplicate);
        }
        SubmissionAttemptEntity attempt = store.markSubmitting(intent.getId());
        PlacementResult result;
        try {
            result = execution.place(new PlaceOrderCommand(intent.getMarketId(), intent.getSelectionId(),
                    intent.getHandicap(), intent.getSide(), intent.getRequestedPrice(), intent.getRequestedSize(),
                    intent.getPersistenceType(), intent.getCustomerRef(), intent.getCustomerOrderRef(),
                    customerStrategyRef, context.prices() == null ? null : context.prices().version()));
        } catch (RuntimeException e) {
            // Anything unexpected after the request may have left: treat as ambiguous and reconcile.
            log.error("Unexpected failure during placement of intent {}", intent.getId(), e);
            result = PlacementResult.unknown("Unexpected client failure: " + e.getClass().getSimpleName());
        }
        OrderIntentEntity updated = store.recordResult(intent.getId(), attempt.getId(), result, command.actor());
        events.publishEvent(UiNotification.of("orders", updated.getId(), Map.of("status", updated.getStatus().name())));
        return toOutcome(updated, result);
    }

    private PlacementOutcome toOutcome(OrderIntentEntity intent, PlacementResult result) {
        PlacementOutcome.Status status = switch (result.outcome()) {
            case PLACED -> PlacementOutcome.Status.PLACED;
            case EXCHANGE_REJECTED -> PlacementOutcome.Status.EXCHANGE_REJECTED;
            case UNKNOWN_OUTCOME -> PlacementOutcome.Status.UNKNOWN_OUTCOME;
            case TECHNICAL_FAILURE -> PlacementOutcome.Status.TECHNICAL_FAILURE;
        };
        String message = switch (status) {
            case PLACED -> "Order accepted by Betfair";
            case EXCHANGE_REJECTED -> "Betfair rejected the order";
            case UNKNOWN_OUTCOME -> "Outcome unknown; the order is being reconciled with Betfair";
            case TECHNICAL_FAILURE -> "Order was not submitted due to a technical failure";
            default -> null;
        };
        return new PlacementOutcome(status, intent.getId(), result.betId(), result.sizeMatched(),
                result.averagePriceMatched(), result.providerErrorCode(), result.instructionErrorCode(), List.of(), message);
    }

    private PlacementOutcome describe(OrderIntentEntity intent, String message) {
        var order = betOrders.findByIntentId(intent.getId());
        PlacementOutcome.Status status = switch (intent.getStatus()) {
            case PLACED -> PlacementOutcome.Status.PLACED;
            case EXCHANGE_REJECTED -> PlacementOutcome.Status.EXCHANGE_REJECTED;
            case TECHNICAL_FAILURE -> PlacementOutcome.Status.TECHNICAL_FAILURE;
            default -> PlacementOutcome.Status.UNKNOWN_OUTCOME;
        };
        return new PlacementOutcome(status, intent.getId(), order.map(o -> o.getBetId()).orElse(null),
                order.map(o -> o.getSizeMatched()).orElse(BigDecimal.ZERO),
                order.map(o -> o.getAveragePriceMatched()).orElse(null), null, null, List.of(), message);
    }

    private void auditRiskRejection(PlacementCommand command, List<RiskViolation> violations) {
        AuditRecord record = "system".equals(command.actor())
                ? AuditRecord.system(AuditAction.ORDER_RISK_REJECTED)
                : AuditRecord.admin(command.actor(), AuditAction.ORDER_RISK_REJECTED);
        auditTrail.recordIndependently(record.outcome(AuditOutcome.DENIED)
                .entity("market_runner", command.marketId() + ":" + command.selectionId())
                .configuration(command.configuration() == null ? null : command.configuration().id(),
                        command.configuration() == null ? null : command.configuration().version())
                .details(Map.of("violations", violations.stream().map(RiskViolation::code).toList(),
                        "mode", command.mode().name())));
    }
}
