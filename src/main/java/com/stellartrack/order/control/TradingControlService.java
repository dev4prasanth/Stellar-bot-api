package com.stellartrack.order.control;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.common.domain.TradingMode;
import com.stellartrack.common.exception.ConflictException;
import com.stellartrack.common.events.UiNotification;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Global trading controls.
 * <ul>
 *   <li><b>Trading mode</b>: AUTOMATIC submits proposals from running configurations without administrator
 *   approval; MANUAL holds every proposal for the administrator to approve or reject. Changing the mode affects new
 *   proposals only: proposals already awaiting approval stay in the queue until decided or expired.</li>
 *   <li><b>Watch</b>: simulation only; no real bet can be placed. Watch is entered and left only through a Watch
 *   session ({@link #enterWatch}, {@link #leaveWatch}); the Manual/Automatic switch is refused while it is active.</li>
 *   <li><b>Stop All</b>: while engaged, no new automatic entry is submitted. It does not cancel or close existing
 *   orders or positions, which can still produce further gains or losses.</li>
 * </ul>
 */
@Service
public class TradingControlService {

    public record ControlState(boolean stopAll, String reason, String changedBy, Instant changedAt,
            TradingMode tradingMode, String tradingModeChangedBy, Instant tradingModeChangedAt) {
    }

    private final GlobalControlRepository repository;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public TradingControlService(GlobalControlRepository repository, AuditTrail auditTrail,
            ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.auditTrail = auditTrail;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ControlState state() {
        GlobalControlEntity g = repository.singleton().orElseThrow();
        return new ControlState(g.isStopAll(), g.getStopAllReason(), g.getChangedBy(), g.getChangedAt(),
                g.getTradingMode(), g.getTradingModeChangedBy(), g.getTradingModeChangedAt());
    }

    /** The current trading mode; MANUAL if the control row cannot be read (fail safe: a human decides). */
    @Transactional(readOnly = true)
    public TradingMode tradingMode() {
        return repository.singleton().map(GlobalControlEntity::getTradingMode).orElse(TradingMode.MANUAL);
    }

    /** True while a Watch session is active: no real bet may be placed. */
    @Transactional(readOnly = true)
    public boolean isWatchActive() {
        return tradingMode() == TradingMode.WATCH;
    }

    /** Switches between the two live modes. Watch is refused here: it is started and stopped with a Watch session. */
    @Transactional
    public ControlState changeTradingMode(TradingMode mode, String actor) {
        if (mode == TradingMode.WATCH) {
            throw new ConflictException("WATCH_REQUIRES_SESSION",
                    "Watch Mode is started from the Watch screen with a starting capital and configurations");
        }
        GlobalControlEntity g = repository.lockSingleton().orElseThrow();
        if (g.getTradingMode() == TradingMode.WATCH) {
            throw new ConflictException("WATCH_MODE_ACTIVE",
                    "Watch Mode is running. Stop Watch Mode on the Watch screen to return to Manual or Automatic");
        }
        apply(g, mode, actor, Map.of());
        return state();
    }

    /**
     * Enters Watch. Runs inside the Watch session's start transaction, which holds the control row lock.
     *
     * @return the live mode that was active before Watch
     */
    @Transactional
    public TradingMode enterWatch(Object sessionId, String actor) {
        GlobalControlEntity g = repository.lockSingleton().orElseThrow();
        TradingMode previous = g.getTradingMode();
        if (previous == TradingMode.WATCH) {
            throw new ConflictException("WATCH_MODE_ACTIVE", "Watch Mode is already running");
        }
        apply(g, TradingMode.WATCH, actor, Map.of("watchSessionId", String.valueOf(sessionId)));
        return previous;
    }

    /** Leaves Watch for a live mode. Runs inside the Watch session's stop transaction. */
    @Transactional
    public void leaveWatch(TradingMode liveMode, Object sessionId, String actor) {
        if (liveMode == null || !liveMode.isLive()) {
            throw new ConflictException("LIVE_MODE_REQUIRED", "Choose Manual or Automatic to return to");
        }
        GlobalControlEntity g = repository.lockSingleton().orElseThrow();
        if (g.getTradingMode() != TradingMode.WATCH) {
            throw new ConflictException("WATCH_MODE_NOT_ACTIVE", "Watch Mode is not running");
        }
        apply(g, liveMode, actor, Map.of("watchSessionId", String.valueOf(sessionId)));
    }

    private void apply(GlobalControlEntity g, TradingMode mode, String actor, Map<String, Object> extra) {
        TradingMode previous = g.getTradingMode();
        if (previous == mode) {
            return;
        }
        g.setTradingMode(mode, actor, clock.instant());
        Map<String, Object> details = new LinkedHashMap<>(extra);
        details.put("from", previous.name());
        details.put("to", mode.name());
        auditTrail.record(AuditRecord.admin(actor, AuditAction.TRADING_MODE_CHANGED).entity("global_control", 1)
                .details(details));
        events.publishEvent(UiNotification.of("automation", null, Map.of("tradingMode", mode.name())));
    }

    @Transactional(readOnly = true)
    public boolean isStopAllEngaged() {
        return repository.singleton().map(GlobalControlEntity::isStopAll).orElse(true);
    }

    @Transactional
    public ControlState engageStopAll(String reason, String actor) {
        GlobalControlEntity g = repository.lockSingleton().orElseThrow();
        g.set(true, reason, actor, clock.instant());
        auditTrail.record(AuditRecord.admin(actor, AuditAction.STOP_ALL_ENGAGED).entity("global_control", 1)
                .details(Map.of("reason", reason == null ? "" : reason)));
        return state();
    }

    @Transactional
    public ControlState releaseStopAll(String actor) {
        GlobalControlEntity g = repository.lockSingleton().orElseThrow();
        g.set(false, null, actor, clock.instant());
        auditTrail.record(AuditRecord.admin(actor, AuditAction.STOP_ALL_RELEASED).entity("global_control", 1));
        return state();
    }
}
