package com.stellartrack.watch.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.automation.application.AutomationService;
import com.stellartrack.capital.application.CapitalService;
import com.stellartrack.capital.application.CapitalService.FundsSnapshot;
import com.stellartrack.capital.application.CapitalService.FundsView;
import com.stellartrack.common.domain.TradingMode;
import com.stellartrack.common.events.UiNotification;
import com.stellartrack.common.exception.BusinessRuleException;
import com.stellartrack.common.exception.ConflictException;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.money.MoneyPolicy;
import com.stellartrack.common.provider.ExchangeAvailability;
import com.stellartrack.configuration.application.ConfigurationService;
import com.stellartrack.configuration.application.ConfigurationService.ResolvedConfiguration;
import com.stellartrack.configuration.domain.BotConfigurationSettings;
import com.stellartrack.configuration.persistence.BotConfigurationEntity;
import com.stellartrack.decision.application.ManualApprovalService;
import com.stellartrack.order.control.TradingControlService;
import com.stellartrack.order.domain.Revocability;
import com.stellartrack.watch.config.WatchProperties;
import com.stellartrack.watch.domain.CapitalSource;
import com.stellartrack.watch.domain.MatchingStatus;
import com.stellartrack.watch.domain.PaperFillEngine;
import com.stellartrack.watch.domain.ResultStatus;
import com.stellartrack.watch.domain.WatchConfigurationState;
import com.stellartrack.watch.domain.WatchSessionStatus;
import com.stellartrack.watch.domain.WatchSettlementCalculator;
import com.stellartrack.watch.persistence.WatchBetEntity;
import com.stellartrack.watch.persistence.WatchBetRepository;
import com.stellartrack.watch.persistence.WatchSessionConfigurationEntity;
import com.stellartrack.watch.persistence.WatchSessionConfigurationRepository;
import com.stellartrack.watch.persistence.WatchSessionEntity;
import com.stellartrack.watch.persistence.WatchSessionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts and stops Watch Mode. Starting Watch switches the global trading mode to WATCH in the same transaction,
 * so from that moment no real bet can be placed; proposals still awaiting approval are expired. Stopping Watch
 * returns to the live mode the administrator chooses. Simulated bets that are still open keep matching and settle
 * into the session they belong to.
 */
@Service
public class WatchSessionService {

    /** @param capitalSource where the starting capital comes from; {@code customAmount} is required for CUSTOM */
    public record StartCommand(String name, CapitalSource capitalSource, BigDecimal customAmount,
            List<UUID> configurationIds, String notes) {
    }

    /** A configuration as offered in the Start Watch dialog; {@code blockers} empty when it can take part. */
    public record EligibleConfiguration(UUID id, String name, int version, String selectionSource, String strategyCode,
            String side, String marketType, String stakingMethod, String liveState, List<String> blockers) {
    }

    public record StartOptions(List<EligibleConfiguration> configurations, Optional<FundsView> realFunds,
            long pendingApprovals, BigDecimal defaultCommissionRate, int slippageTicks, String fillModelVersion,
            TradingMode currentMode) {
    }

    private final WatchSessionRepository sessions;
    private final WatchSessionConfigurationRepository sessionConfigurations;
    private final WatchBetRepository bets;
    private final WatchCapitalService capital;
    private final ConfigurationService configurations;
    private final AutomationService automation;
    private final CapitalService accountCapital;
    private final ManualApprovalService approvals;
    private final TradingControlService tradingControl;
    private final ExchangeAvailability exchange;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final WatchProperties properties;
    private final Clock clock;

    public WatchSessionService(WatchSessionRepository sessions, WatchSessionConfigurationRepository sessionConfigurations,
            WatchBetRepository bets, WatchCapitalService capital, ConfigurationService configurations, AutomationService automation,
            CapitalService accountCapital, ManualApprovalService approvals, TradingControlService tradingControl,
            ExchangeAvailability exchange, AuditTrail auditTrail, ApplicationEventPublisher events,
            WatchProperties properties, Clock clock) {
        this.sessions = sessions;
        this.sessionConfigurations = sessionConfigurations;
        this.bets = bets;
        this.capital = capital;
        this.configurations = configurations;
        this.automation = automation;
        this.accountCapital = accountCapital;
        this.approvals = approvals;
        this.tradingControl = tradingControl;
        this.exchange = exchange;
        this.auditTrail = auditTrail;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<WatchSessionEntity> active() {
        return sessions.findFirstByStatus(WatchSessionStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public WatchSessionEntity require(UUID sessionId) {
        return sessions.findById(sessionId).orElseThrow(() -> new NotFoundException("Watch session", sessionId));
    }

    @Transactional(readOnly = true)
    public List<WatchSessionConfigurationEntity> configurations(UUID sessionId) {
        return sessionConfigurations.findBySessionIdOrderByConfigurationNameAsc(sessionId);
    }

    @Transactional(readOnly = true)
    public StartOptions startOptions() {
        List<EligibleConfiguration> eligible = configurations.list(false).stream()
                .map(entity -> eligibility(entity, configurations.current(entity.getId()))).toList();
        return new StartOptions(eligible, accountCapital.latest(), approvals.queue().size(),
                properties.defaultCommissionRate(), properties.slippageTicks(), PaperFillEngine.VERSION,
                tradingControl.tradingMode());
    }

    @Transactional
    public WatchSessionEntity start(StartCommand command, String actor) {
        if (active().isPresent() || tradingControl.isWatchActive()) {
            throw new ConflictException("WATCH_MODE_ACTIVE", "Watch Mode is already running");
        }
        automation.activeLive().ifPresent(running -> {
            throw new ConflictException("STOP_BEFORE_MODE_CHANGE", "Configuration \"" + running.getName()
                    + "\" is running in live trading. Stop it before starting Watch Mode");
        });
        List<ResolvedConfiguration> selected = validatedConfigurations(command.configurationIds());
        Capital starting = startingCapital(command);
        Instant now = clock.instant();
        WatchSessionEntity session = WatchSessionEntity.start(sessionName(command.name(), now), actor, now,
                tradingControl.tradingMode(), command.capitalSource(), starting.amount(), starting.currencyCode(),
                starting.snapshotId(), starting.discountRate(), properties.defaultCommissionRate(),
                PaperFillEngine.VERSION, properties.slippageTicks(), WatchSettlementCalculator.RULE_VERSION,
                blankToNull(command.notes()));
        session.previousTradingMode(tradingControl.enterWatch(session.getId(), actor));
        sessions.save(session);
        selected.forEach(c -> sessionConfigurations.save(toSessionConfiguration(session.getId(), c)));
        capital.open(session);
        int expired = approvals.expireAllPending("Watch Mode started", actor);
        auditTrail.record(AuditRecord.admin(actor, AuditAction.WATCH_SESSION_STARTED).entity("watch_session", session.getId())
                .details(Map.of("capitalSource", command.capitalSource().name(), "startingCapital", starting.amount(),
                        "configurations", selected.stream().map(c -> c.name() + " v" + c.version()).toList(),
                        "previousMode", session.getPreviousTradingMode().name(), "pendingApprovalsExpired", expired)));
        notify(session);
        return session;
    }

    @Transactional
    public WatchSessionEntity stop(UUID sessionId, TradingMode resumeMode, String reason, String actor) {
        WatchSessionEntity session = require(sessionId);
        if (!session.isActive()) {
            throw new ConflictException("WATCH_SESSION_NOT_ACTIVE", "This Watch session has already stopped");
        }
        TradingMode resume = resumeMode == null ? TradingMode.MANUAL : resumeMode;
        String why = reason == null || reason.isBlank() ? "Stopped by administrator" : reason.trim();
        tradingControl.leaveWatch(resume, session.getId(), actor);
        session.stop(resume, why, actor, clock.instant());
        sessionConfigurations.findBySessionIdOrderByConfigurationNameAsc(sessionId)
                .forEach(c -> c.changeState(WatchConfigurationState.STOPPED, "Session stopped"));
        auditTrail.record(AuditRecord.admin(actor, AuditAction.WATCH_SESSION_STOPPED).entity("watch_session", sessionId)
                .details(Map.of("resumedMode", resume.name(), "reason", why)));
        notify(session);
        return session;
    }

    /**
     * Revokes the unmatched remainder of a simulated bet: an unmatched bet is revoked in full, a partially matched bet
     * keeps its matched part. Its state is re-read under a row lock, so a bet that matched or closed meanwhile is
     * refused.
     */
    @Transactional
    public WatchBetEntity revokeBet(UUID betId, String actor) {
        WatchBetEntity bet = bets.lockById(betId).orElseThrow(() -> new NotFoundException("Watch bet", betId));
        if (!bet.getMatchingStatus().isOpen() || bet.getResultStatus() != ResultStatus.OPEN) {
            // the same derivation as v_watch_bet.display_status
            String status = switch (bet.getResultStatus()) {
                case OPEN -> bet.getMatchingStatus() == MatchingStatus.VOIDED ? "VOID"
                        : bet.getSizeMatched().signum() > 0 ? "MATCHED" : "UNMATCHED";
                case NOT_MATCHED -> "UNMATCHED";
                default -> bet.getResultStatus().name();
            };
            throw new BusinessRuleException("NOTHING_TO_CANCEL", Revocability.ofWatchBet(status).reason());
        }
        BigDecimal revoked = bet.getSizeRemaining();
        bet.revoke("Revoked by " + actor, clock.instant());
        auditTrail.record(AuditRecord.admin(actor, AuditAction.WATCH_BET_REVOKED).entity("watch_bet", betId)
                .details(Map.of("sessionId", bet.getSessionId().toString(), "sizeRevoked", revoked)));
        events.publishEvent(UiNotification.of("watch", bet.getSessionId(), Map.of("bet", betId.toString(),
                "matching", bet.getMatchingStatus().name())));
        return bet;
    }

    @Transactional
    public WatchSessionConfigurationEntity pause(UUID sessionId, UUID configurationId, String actor) {
        return changeConfiguration(sessionId, configurationId, WatchConfigurationState.PAUSED, "Paused by " + actor);
    }

    @Transactional
    public WatchSessionConfigurationEntity resume(UUID sessionId, UUID configurationId, String actor) {
        return changeConfiguration(sessionId, configurationId, WatchConfigurationState.RUNNING, null);
    }

    /** Ends one configuration's participation, e.g. when a stop rule is reached. */
    @Transactional
    public void stopConfiguration(UUID sessionConfigurationId, String reason) {
        sessionConfigurations.findById(sessionConfigurationId)
                .ifPresent(c -> c.changeState(WatchConfigurationState.STOPPED, reason));
    }

    private WatchSessionConfigurationEntity changeConfiguration(UUID sessionId, UUID configurationId,
            WatchConfigurationState target, String reason) {
        if (!require(sessionId).isActive()) {
            throw new ConflictException("WATCH_SESSION_NOT_ACTIVE", "This Watch session has already stopped");
        }
        WatchSessionConfigurationEntity c = sessionConfigurations.findBySessionIdAndConfigurationId(sessionId, configurationId)
                .orElseThrow(() -> new NotFoundException("Watch session configuration", configurationId));
        if (c.getState() == WatchConfigurationState.STOPPED) {
            throw new ConflictException("WATCH_CONFIGURATION_STOPPED", "This configuration has stopped: " + c.getStateReason());
        }
        c.changeState(target, reason);
        events.publishEvent(UiNotification.of("watch", sessionId, Map.of("configuration", configurationId.toString(),
                "state", target.name())));
        return c;
    }

    private List<ResolvedConfiguration> validatedConfigurations(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessRuleException("WATCH_CONFIGURATION_REQUIRED", "Choose at least one configuration to watch");
        }
        List<ResolvedConfiguration> selected = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(ids)) {
            BotConfigurationEntity entity = configurations.require(id);
            ResolvedConfiguration configuration = configurations.current(id);
            EligibleConfiguration eligibility = eligibility(entity, configuration);
            eligibility.blockers().forEach(b -> blockers.add(entity.getName() + ": " + b));
            selected.add(configuration);
        }
        if (!blockers.isEmpty()) {
            throw new BusinessRuleException("WATCH_START_BLOCKED", "Watch Mode cannot start", Map.of("blockers", blockers));
        }
        return selected;
    }

    private EligibleConfiguration eligibility(BotConfigurationEntity entity, ResolvedConfiguration configuration) {
        List<String> blockers = new ArrayList<>();
        if (entity.getArchivedAt() != null) {
            blockers.add("Archived");
        }
        if (!exchange.isAvailable()) {
            blockers.add(exchange.unavailableReason().orElse("Exchange unavailable"));
        }
        blockers.addAll(automation.selectionBlockers(configuration));
        BotConfigurationSettings settings = configuration.settings();
        return new EligibleConfiguration(configuration.id(), configuration.name(), configuration.version(),
                settings.selection().source().name(), settings.selection().strategyCode(),
                settings.market().side().name(), settings.market().marketType().name(),
                settings.staking().method().name(), entity.getOperationalState().name(), blockers);
    }

    private record Capital(BigDecimal amount, String currencyCode, UUID snapshotId, BigDecimal discountRate) {
    }

    private Capital startingCapital(StartCommand command) {
        if (command.capitalSource() == null) {
            throw new BusinessRuleException("WATCH_CAPITAL_REQUIRED", "Choose the starting capital");
        }
        Optional<FundsView> latest = accountCapital.latest();
        BigDecimal discount = MoneyPolicy.nullToZero(latest.map(FundsView::discountRate).orElse(null));
        String currency = latest.map(FundsView::currencyCode).orElse(null);
        if (command.capitalSource() == CapitalSource.CUSTOM) {
            if (!MoneyPolicy.isPositive(command.customAmount())) {
                throw new BusinessRuleException("WATCH_CAPITAL_INVALID", "Enter a starting capital greater than zero");
            }
            return new Capital(MoneyPolicy.amount(command.customAmount()), currency, null, discount);
        }
        FundsSnapshot snapshot = accountCapital.freshSnapshot().orElseThrow(() -> new BusinessRuleException(
                "WATCH_REAL_BALANCE_UNAVAILABLE", "The real Betfair balance is unavailable or stale; enter a custom amount"));
        if (!MoneyPolicy.isPositive(snapshot.funds().availableToBet())) {
            throw new BusinessRuleException("WATCH_CAPITAL_INVALID", "The real available balance is zero");
        }
        return new Capital(MoneyPolicy.amount(snapshot.funds().availableToBet()), snapshot.funds().currencyCode(),
                snapshot.id(), MoneyPolicy.nullToZero(snapshot.funds().discountRate()));
    }

    private static WatchSessionConfigurationEntity toSessionConfiguration(UUID sessionId, ResolvedConfiguration c) {
        BotConfigurationSettings settings = c.settings();
        return new WatchSessionConfigurationEntity(sessionId, c.id(), c.version(), c.name(),
                settings.selection().source().name(), settings.selection().strategyCode(), settings.market().side().name(),
                settings.market().marketType().name(), settings.staking().method().name());
    }

    private void notify(WatchSessionEntity session) {
        events.publishEvent(UiNotification.of("watch", session.getId(), Map.of("status", session.getStatus().name())));
    }

    private static String sessionName(String name, Instant now) {
        String trimmed = blankToNull(name);
        return trimmed != null ? (trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed) : "Watch " + now;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
