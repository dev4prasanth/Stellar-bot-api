package com.stellartrack.order.application;

import com.stellartrack.common.domain.OperatingMode;
import com.stellartrack.common.events.UiNotification;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.configuration.application.ConfigurationService;
import com.stellartrack.configuration.application.ConfigurationService.ResolvedConfiguration;
import com.stellartrack.decision.application.DecisionService;
import com.stellartrack.decision.application.RecommendationApprovedEvent;
import com.stellartrack.decision.persistence.DecisionSnapshotRepository;
import com.stellartrack.decision.persistence.RecommendationEntity;
import com.stellartrack.order.domain.OrderPurpose;
import com.stellartrack.order.risk.RiskViolation;
import com.stellartrack.staking.domain.StakeCalculation;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Submits an administrator-approved proposal with the exact proposed terms (price and stake frozen in the
 * decision snapshot). Approval does not bypass anything: the full pre-trade validation runs, including
 * price movement against the proposed price, funds, race state, freshness, Stop All and risk limits.
 */
@Component
public class ApprovedRecommendationExecutor {

    private static final Logger log = LoggerFactory.getLogger(ApprovedRecommendationExecutor.class);

    private final DecisionService decisions;
    private final DecisionSnapshotRepository snapshots;
    private final ConfigurationService configurations;
    private final OrderPlacementService placement;
    private final AutomationSessionProvider sessions;
    private final ApplicationEventPublisher events;
    private final JsonCodec json;

    public ApprovedRecommendationExecutor(DecisionService decisions, DecisionSnapshotRepository snapshots,
            ConfigurationService configurations, OrderPlacementService placement, AutomationSessionProvider sessions,
            ApplicationEventPublisher events, JsonCodec json) {
        this.decisions = decisions;
        this.snapshots = snapshots;
        this.configurations = configurations;
        this.placement = placement;
        this.sessions = sessions;
        this.events = events;
        this.json = json;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApproved(RecommendationApprovedEvent event) {
        try {
            execute(event);
        } catch (RuntimeException e) {
            log.error("Approved recommendation {} could not be executed", event.recommendationId(), e);
            decisions.noteNotExecuted(event.recommendationId(), "Execution failed: " + e.getClass().getSimpleName());
        }
    }

    void execute(RecommendationApprovedEvent event) {
        RecommendationEntity recommendation = decisions.require(event.recommendationId());
        StakeCalculation stake = snapshots.findByRecommendationId(recommendation.getId())
                .map(s -> s.getStaking() == null ? null : json.read(s.getStaking(), StakeCalculation.class))
                .orElse(null);
        ResolvedConfiguration configuration = recommendation.getConfigurationId() == null ? null
                : configurations.resolve(recommendation.getConfigurationId(), recommendation.getConfigurationVersion());
        // An approved proposal is a Manual-mode bet: the administrator confirmed it. Its origin (recommendation mode)
        // still decides which origin-specific pre-trade rules apply.
        PlacementCommand command = new PlacementCommand(recommendation.getMode(), OperatingMode.MANUAL, OrderPurpose.ENTRY, null,
                recommendation.getId(), configuration, recommendation.getMarketId(), recommendation.getSelectionId(),
                recommendation.getSide(), recommendation.getProposedPrice(), stake,
                configuration == null ? com.stellartrack.order.port.PersistenceType.LAPSE
                        : configuration.settings().timing().persistenceType(),
                "approval-" + recommendation.getId(), event.approvedBy(), recommendation.getProposedPrice(), false,
                configuration == null ? null : sessions.activeSession(configuration.id()).orElse(null));
        PlacementOutcome outcome = placement.submit(command);
        if (outcome.status() == PlacementOutcome.Status.RISK_REJECTED
                || outcome.status() == PlacementOutcome.Status.RECONFIRMATION_REQUIRED) {
            decisions.noteNotExecuted(recommendation.getId(), "Approved but not submitted: " + outcome.violations()
                    .stream().map(RiskViolation::message).collect(Collectors.joining("; ")));
        }
        events.publishEvent(UiNotification.of("recommendations", recommendation.getId(),
                Map.of("execution", outcome.status().name())));
    }
}
