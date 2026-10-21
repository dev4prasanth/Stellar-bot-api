package com.stellartrack.order.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditOutcome;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.order.domain.OrderIntentStatus;
import com.stellartrack.order.persistence.BetOrderEntity;
import com.stellartrack.order.persistence.BetOrderRepository;
import com.stellartrack.order.persistence.OrderIntentEntity;
import com.stellartrack.order.persistence.OrderIntentRepository;
import com.stellartrack.order.persistence.SubmissionAttemptEntity;
import com.stellartrack.order.persistence.SubmissionAttemptRepository;
import com.stellartrack.order.port.PlacementResult;
import com.stellartrack.order.port.ProviderOrderState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundaries around a submission. Each step commits on its own: the intent is durable before
 * Betfair is contacted, and the outcome is durable as soon as Betfair answers. Database and provider
 * operations are never assumed to be atomic.
 */
@Component
public class OrderIntentStore {

    private final OrderIntentRepository intents;
    private final SubmissionAttemptRepository attempts;
    private final BetOrderRepository betOrders;
    private final AuditTrail auditTrail;
    private final JsonCodec json;
    private final Clock clock;

    public OrderIntentStore(OrderIntentRepository intents, SubmissionAttemptRepository attempts,
            BetOrderRepository betOrders, AuditTrail auditTrail, JsonCodec json, Clock clock) {
        this.intents = intents;
        this.attempts = attempts;
        this.betOrders = betOrders;
        this.auditTrail = auditTrail;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderIntentEntity createIntent(PlacementCommand command, MarketType marketType) {
        OrderIntentEntity created = OrderIntentEntity.create(command.recommendationId(),
                command.configuration() == null ? null : command.configuration().id(),
                command.configuration() == null ? null : command.configuration().version(),
                command.mode(), command.tradingMode(), command.purpose(), command.linkedIntentId(), command.marketId(), command.selectionId(),
                BigDecimal.ZERO, command.side(), marketType, command.price(), command.stake().stake(),
                command.persistenceType(), json.write(command.stake()), command.idempotencyKey(), command.actor(),
                clock.instant());
        created.assignDutchGroup(command.dutchGroupId());
        OrderIntentEntity intent = intents.saveAndFlush(created);
        auditTrail.record(base(command.actor(), AuditAction.ORDER_INTENT_CREATED, intent).outcome(AuditOutcome.INFO));
        return intent;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SubmissionAttemptEntity markSubmitting(UUID intentId) {
        OrderIntentEntity intent = intents.findById(intentId).orElseThrow();
        intent.transition(OrderIntentStatus.SUBMITTING, null, clock.instant());
        int attemptNo = attempts.countByIntentId(intentId) + 1;
        return attempts.save(new SubmissionAttemptEntity(intentId, attemptNo, clock.instant()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderIntentEntity recordResult(UUID intentId, UUID attemptId, PlacementResult result, String actor) {
        Instant now = clock.instant();
        OrderIntentEntity intent = intents.findById(intentId).orElseThrow();
        attempts.findById(attemptId).orElseThrow().finish(result, now);
        OrderIntentStatus status = switch (result.outcome()) {
            case PLACED -> OrderIntentStatus.PLACED;
            case EXCHANGE_REJECTED -> OrderIntentStatus.EXCHANGE_REJECTED;
            case UNKNOWN_OUTCOME -> OrderIntentStatus.UNKNOWN_OUTCOME;
            case TECHNICAL_FAILURE -> OrderIntentStatus.TECHNICAL_FAILURE;
        };
        intent.transition(status, result.detail(), now);
        if (result.outcome() == PlacementResult.Outcome.PLACED && result.betId() != null) {
            BetOrderEntity order = new BetOrderEntity(result.betId(), intentId, intent.getMarketId(),
                    intent.getSelectionId(), intent.getHandicap(), intent.getSide(), now);
            BigDecimal matched = result.sizeMatched() == null ? BigDecimal.ZERO : result.sizeMatched();
            order.apply(new ProviderOrderState(result.betId(), intent.getMarketId(), intent.getSelectionId(),
                    intent.getHandicap(), intent.getSide(), intent.getRequestedPrice(), intent.getRequestedSize(),
                    result.averagePriceMatched(), matched, intent.getRequestedSize().subtract(matched), BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, "EXECUTION_COMPLETE".equals(result.orderStatus()),
                    result.placedAt(), null, intent.getPersistenceType().name(), "LIMIT", intent.getCustomerOrderRef(),
                    null, ProviderOrderState.Source.PLACE), now);
            betOrders.save(order);
        }
        AuditAction action = switch (result.outcome()) {
            case PLACED -> AuditAction.ORDER_PLACED;
            case EXCHANGE_REJECTED -> AuditAction.ORDER_EXCHANGE_REJECTED;
            case UNKNOWN_OUTCOME -> AuditAction.ORDER_OUTCOME_UNKNOWN;
            case TECHNICAL_FAILURE -> AuditAction.ORDER_TECHNICAL_FAILURE;
        };
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("betId", result.betId());
        details.put("providerStatus", result.providerStatus());
        details.put("providerErrorCode", result.providerErrorCode());
        details.put("instructionErrorCode", result.instructionErrorCode());
        details.values().removeIf(java.util.Objects::isNull);
        auditTrail.record(base(actor, action, intent)
                .outcome(result.outcome() == PlacementResult.Outcome.PLACED ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
                .details(details));
        return intent;
    }

    private static AuditRecord base(String actor, AuditAction action, OrderIntentEntity intent) {
        AuditRecord record = "system".equals(actor) ? AuditRecord.system(action) : AuditRecord.admin(actor, action);
        return record.entity("order_intent", intent.getId())
                .configuration(intent.getConfigurationId(), intent.getConfigurationVersion());
    }
}
