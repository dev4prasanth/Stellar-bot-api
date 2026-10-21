package com.stellartrack.watch.application;

import com.stellartrack.automation.application.ExecutionLeaseService;
import com.stellartrack.common.web.CorrelationIds;
import com.stellartrack.watch.config.WatchProperties;
import com.stellartrack.watch.persistence.WatchSessionConfigurationEntity;
import com.stellartrack.watch.persistence.WatchSessionConfigurationRepository;
import com.stellartrack.watch.persistence.WatchSessionEntity;
import com.stellartrack.watch.persistence.WatchSessionRepository;
import java.net.InetAddress;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backend Watch loop, independent of any browser and of the live automation loop (own lease). Each pass evaluates
 * the active session's running configurations, re-checks open simulated bets and settles markets whose result was
 * captured. Simulated bets of a stopped session keep matching and settling until complete.
 */
@Component
public class WatchWorker {

    private static final Logger log = LoggerFactory.getLogger(WatchWorker.class);

    private final WatchSessionService sessionService;
    private final WatchSessionRepository sessions;
    private final WatchSessionConfigurationRepository sessionConfigurations;
    private final WatchEvaluator evaluator;
    private final WatchFillService fills;
    private final WatchSettlementService settlements;
    private final ExecutionLeaseService leases;
    private final WatchProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String ownerId;

    public WatchWorker(WatchSessionService sessionService, WatchSessionRepository sessions,
            WatchSessionConfigurationRepository sessionConfigurations, WatchEvaluator evaluator, WatchFillService fills,
            WatchSettlementService settlements, ExecutionLeaseService leases, WatchProperties properties, Clock clock,
            PlatformTransactionManager txManager) {
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.sessionConfigurations = sessionConfigurations;
        this.evaluator = evaluator;
        this.fills = fills;
        this.settlements = settlements;
        this.leases = leases;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.ownerId = hostName() + ":" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${stellartrack.watch.tick-interval}", initialDelay = 15_000)
    public void tick() {
        if (!properties.workerEnabled()
                || !leases.tryAcquire(ExecutionLeaseService.WATCH_LEASE, ownerId, properties.leaseTtl())) {
            return;
        }
        sessionService.active().ifPresent(session ->
                CorrelationIds.withCorrelationId("watch-" + session.getId(), () -> evaluate(session)));
        CorrelationIds.withCorrelationId("watch-fills", this::maintainBets);
    }

    private void evaluate(WatchSessionEntity session) {
        String sessionError = null;
        for (WatchSessionConfigurationEntity configuration : evaluator.running(session)) {
            String notice;
            String stop = null;
            try {
                WatchEvaluator.ConfigurationTick result = evaluator.tick(session, configuration);
                notice = result.notice();
                stop = result.stopReason() == null ? null : "Automatic stop: " + result.stopReason();
            } catch (RuntimeException e) {
                log.error("Watch configuration {} failed", configuration.getId(), e);
                notice = null;
                stop = "Evaluation error: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage());
                sessionError = stop;
            }
            record(configuration.getId(), notice, stop);
        }
        String error = sessionError;
        tx.executeWithoutResult(s -> sessions.findById(session.getId()).ifPresent(fresh -> fresh.ticked(clock.instant(), error)));
    }

    private void record(UUID sessionConfigurationId, String notice, String stopReason) {
        if (stopReason != null) {
            sessionService.stopConfiguration(sessionConfigurationId, stopReason);
            evaluator.forget(sessionConfigurationId);
        }
        tx.executeWithoutResult(s -> sessionConfigurations.findById(sessionConfigurationId)
                .ifPresent(fresh -> fresh.ticked(clock.instant(), notice)));
    }

    private void maintainBets() {
        try {
            fills.recheckOpenBets();
            settlements.settleCapturedMarkets();
        } catch (RuntimeException e) {
            log.warn("Watch bet maintenance failed: {}", e.getMessage());
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }
}
