package com.stellartrack.automation.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditOutcome;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.automation.config.AutomationProperties;
import com.stellartrack.automation.persistence.AutomationRunEntity;
import com.stellartrack.automation.persistence.AutomationRunRepository;
import com.stellartrack.capital.application.CapitalService;
import com.stellartrack.common.events.UiNotification;
import com.stellartrack.common.domain.TradingMode;
import com.stellartrack.common.exception.BusinessRuleException;
import com.stellartrack.common.exception.ConflictException;
import com.stellartrack.common.provider.ExchangeAvailability;
import com.stellartrack.configuration.application.ConfigurationService;
import com.stellartrack.configuration.application.ConfigurationService.ResolvedConfiguration;
import com.stellartrack.configuration.domain.BotConfigurationSettings;
import com.stellartrack.configuration.domain.OperationalState;
import com.stellartrack.configuration.persistence.BotConfigurationEntity;
import com.stellartrack.decision.application.ManualApprovalService;
import com.stellartrack.decision.strategy.SelectionStrategy;
import com.stellartrack.decision.strategy.StrategyRegistry;
import com.stellartrack.messaging.application.LayerFeedMonitor;
import com.stellartrack.messaging.application.LayerFeedStatus;
import com.stellartrack.order.application.AutomationSessionProvider;
import com.stellartrack.order.application.PlacementCommand;
import com.stellartrack.order.control.TradingControlService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Start / Stop / Stop All and restart recovery for Automatic Mode. Starting the application never starts
 * betting: running configurations are stopped on restart under the NEVER_RESUME policy.
 */
@Service
public class AutomationService implements AutomationSessionProvider, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);

    private final ConfigurationService configurations;
    private final AutomationRunRepository runs;
    private final StrategyRegistry strategies;
    private final LayerFeedMonitor feeds;
    private final TradingControlService tradingControl;
    private final ExchangeAvailability exchange;
    private final CapitalService capital;
    private final ManualApprovalService approvals;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final AutomationProperties properties;
    private final Clock clock;

    public AutomationService(ConfigurationService configurations, AutomationRunRepository runs,
            StrategyRegistry strategies, LayerFeedMonitor feeds, TradingControlService tradingControl,
            ExchangeAvailability exchange, CapitalService capital, ManualApprovalService approvals, AuditTrail auditTrail,
            ApplicationEventPublisher events, AutomationProperties properties, Clock clock) {
        this.configurations = configurations;
        this.runs = runs;
        this.strategies = strategies;
        this.feeds = feeds;
        this.tradingControl = tradingControl;
        this.exchange = exchange;
        this.capital = capital;
        this.approvals = approvals;
        this.auditTrail = auditTrail;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public AutomationRunEntity start(UUID configurationId, String actor) {
        BotConfigurationEntity entity = configurations.require(configurationId);
        if (entity.getArchivedAt() != null) {
            throw new BusinessRuleException("CONFIGURATION_ARCHIVED", "Archived configurations cannot be started");
        }
        live().stream().filter(other -> !other.getId().equals(configurationId)).findFirst().ifPresent(other -> {
            throw new ConflictException("ANOTHER_CONFIGURATION_RUNNING", "Configuration \"" + other.getName()
                    + "\" is running. Only one configuration runs at a time in live trading: stop it or switch to this one");
        });
        ResolvedConfiguration configuration = configurations.current(configurationId);
        List<String> blockers = startBlockers(configuration);
        if (!blockers.isEmpty()) {
            auditTrail.recordIndependently(AuditRecord.admin(actor, AuditAction.AUTOMATION_STARTED)
                    .entity("bot_configuration", configurationId).outcome(AuditOutcome.DENIED)
                    .configuration(configurationId, configuration.version()).details(Map.of("blockers", blockers)));
            throw new BusinessRuleException("AUTOMATION_START_BLOCKED", "Configuration cannot be started",
                    Map.of("blockers", blockers));
        }
        Instant now = clock.instant();
        entity.transition(OperationalState.STARTING, "Starting", now);
        entity.transition(OperationalState.RUNNING, "Running since " + now, now);
        AutomationRunEntity run = runs.save(AutomationRunEntity.start(configurationId, configuration.version(), actor, now));
        auditTrail.record(AuditRecord.admin(actor, AuditAction.AUTOMATION_STARTED).entity("automation_run", run.getId())
                .configuration(configurationId, configuration.version()));
        notify(configurationId, OperationalState.RUNNING, null);
        return run;
    }

    /** Pre-start checks; each blocker is a human-readable reason shown to the administrator. */
    public List<String> startBlockers(ResolvedConfiguration configuration) {
        List<String> blockers = new java.util.ArrayList<>();
        if (!exchange.isAvailable()) {
            blockers.add(exchange.unavailableReason().orElse("Exchange unavailable"));
        }
        if (tradingControl.isStopAllEngaged()) {
            blockers.add("Stop All is engaged; release it before starting automation");
        }
        blockers.addAll(selectionBlockers(configuration));
        return blockers;
    }

    /**
     * Why the configuration's selection source cannot run unattended right now (empty when it can). Shared by live
     * automation and Watch Mode.
     */
    public List<String> selectionBlockers(ResolvedConfiguration configuration) {
        List<String> blockers = new java.util.ArrayList<>();
        BotConfigurationSettings settings = configuration.settings();
        switch (settings.selection().source()) {
            case ADMIN_SELECTED -> blockers.add("ADMIN_SELECTED configurations are only for bets placed from Trading → Race markets");
            case PREDICTION -> {
                try {
                    SelectionStrategy strategy = strategies.requirePrediction(settings.selection().strategyCode());
                    strategy.requiredLayers().stream().map(feeds::status).filter(s -> !s.isUsable())
                            .forEach(s -> blockers.add(s.layer() + " feed is " + s.state() + ": " + s.reason()));
                } catch (RuntimeException e) {
                    blockers.add(e.getMessage());
                }
            }
            case MARKET_CRITERIA -> {
                // explicit filters only; nothing further to check
            }
        }
        return blockers;
    }

    @Transactional
    public void stop(UUID configurationId, String reason, String actor) {
        BotConfigurationEntity entity = configurations.require(configurationId);
        if (entity.getOperationalState() == OperationalState.STOPPED) {
            return;
        }
        Instant now = clock.instant();
        if (entity.getOperationalState() == OperationalState.RUNNING) {
            entity.transition(OperationalState.STOPPING, reason, now);
        }
        entity.transition(OperationalState.STOPPED, reason, now);
        runs.findByConfigurationIdAndStoppedAtIsNull(configurationId)
                .ifPresent(run -> run.stop(OperationalState.STOPPED, reason, now));
        AuditRecord record = "system".equals(actor) ? AuditRecord.system(AuditAction.AUTOMATION_STOPPED)
                : AuditRecord.admin(actor, AuditAction.AUTOMATION_STOPPED);
        auditTrail.record(record.entity("bot_configuration", configurationId).details(Map.of("reason", reason)));
        notify(configurationId, OperationalState.STOPPED, reason);
    }

    @Transactional
    public void markError(UUID configurationId, String error) {
        BotConfigurationEntity entity = configurations.require(configurationId);
        Instant now = clock.instant();
        if (entity.getOperationalState().canTransitionTo(OperationalState.ERROR)) {
            entity.transition(OperationalState.ERROR, error, now);
        }
        runs.findByConfigurationIdAndStoppedAtIsNull(configurationId)
                .ifPresent(run -> run.stop(OperationalState.ERROR, error, now));
        auditTrail.record(AuditRecord.system(AuditAction.AUTOMATION_ERROR).entity("bot_configuration", configurationId)
                .outcome(AuditOutcome.FAILURE).details(Map.of("error", error)));
        notify(configurationId, OperationalState.ERROR, error);
    }

    /**
     * Stops live trading in the current mode: the active configuration places no new bet, its proposals still awaiting
     * approval are withdrawn, and its bets already placed stay on Betfair and settle as normal.
     *
     * @return the configurations that were stopped
     */
    @Transactional
    public List<UUID> stopLive(String reason, String actor) {
        String why = reason == null || reason.isBlank() ? "Stopped by administrator" : reason.trim();
        List<UUID> stopped = live().stream().map(BotConfigurationEntity::getId).toList();
        for (UUID id : stopped) {
            stop(id, why, actor);
            approvals.withdrawPending(id, "Configuration stopped: " + why, actor);
        }
        return stopped;
    }

    /**
     * Switches live trading to another configuration in one step: the active one is stopped (as {@link #stopLive}) and
     * the target started. If the target cannot start, nothing changes and the active configuration keeps running.
     */
    @Transactional
    public AutomationRunEntity switchTo(UUID configurationId, String actor) {
        BotConfigurationEntity target = configurations.require(configurationId);
        if (target.getOperationalState() == OperationalState.RUNNING) {
            return runs.findByConfigurationIdAndStoppedAtIsNull(configurationId).orElseThrow();
        }
        stopLive("Switched to configuration \"" + target.getName() + "\"", actor);
        return start(configurationId, actor);
    }

    /**
     * Changes the live trading mode. Refused while a configuration runs: live trading is stopped first (its bets are
     * reviewed and its pending proposals withdrawn) so a running configuration never silently changes mode.
     */
    @Transactional
    public TradingControlService.ControlState changeTradingMode(TradingMode mode, String actor) {
        live().stream().findFirst().ifPresent(running -> {
            throw new ConflictException("STOP_BEFORE_MODE_CHANGE", "Configuration \"" + running.getName()
                    + "\" is running. Stop it before changing the trading mode");
        });
        return tradingControl.changeTradingMode(mode, actor);
    }

    /** The configuration running in live trading, if any. */
    @Transactional(readOnly = true)
    public Optional<BotConfigurationEntity> activeLive() {
        return live().stream().findFirst();
    }

    /** Configurations running (or starting / stopping) in live trading. */
    private List<BotConfigurationEntity> live() {
        return configurations.inStates(List.of(OperationalState.RUNNING, OperationalState.STARTING, OperationalState.STOPPING));
    }

    /** Engages Stop All and stops every running configuration. Existing orders are NOT cancelled. */
    @Transactional
    public void stopAll(String reason, String actor) {
        tradingControl.engageStopAll(reason, actor);
        for (BotConfigurationEntity entity : configurations.inStates(
                List.of(OperationalState.RUNNING, OperationalState.STARTING, OperationalState.STOPPING))) {
            stop(entity.getId(), "Stop All: " + (reason == null ? "engaged by administrator" : reason), actor);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlacementCommand.AutomationSession> activeSession(UUID configurationId) {
        return runs.findByConfigurationIdAndStoppedAtIsNull(configurationId).map(this::session);
    }

    public PlacementCommand.AutomationSession session(AutomationRunEntity run) {
        return new PlacementCommand.AutomationSession(run.getId(), run.getStartedAt(), bankAtStart(run));
    }

    private BigDecimal bankAtStart(AutomationRunEntity run) {
        ResolvedConfiguration configuration = configurations.resolve(run.getConfigurationId(), run.getConfigurationVersion());
        if (configuration.settings().capital() != null && configuration.settings().capital().allocatedAmount() != null) {
            return configuration.settings().capital().allocatedAmount();
        }
        return capital.latest().map(CapitalService.FundsView::availableToBet).orElse(null);
    }

    /** Restart recovery: never resume live betting automatically. */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<BotConfigurationEntity> active = configurations.inStates(
                List.of(OperationalState.STARTING, OperationalState.RUNNING, OperationalState.STOPPING));
        for (BotConfigurationEntity entity : active) {
            String reason = "Application restarted; automatic betting not resumed (restart policy "
                    + properties.restartPolicy() + ")";
            stop(entity.getId(), reason, "system");
            log.warn("Configuration {} was {} at startup and has been stopped", entity.getName(), entity.getOperationalState());
        }
    }

    private void notify(UUID configurationId, OperationalState state, String reason) {
        events.publishEvent(UiNotification.of("automation", configurationId,
                reason == null ? Map.of("state", state.name()) : Map.of("state", state.name(), "reason", reason)));
    }

    public List<LayerFeedStatus> feedStatuses() {
        return java.util.Arrays.stream(com.stellartrack.messaging.domain.Layer.values()).map(feeds::status).toList();
    }
}
