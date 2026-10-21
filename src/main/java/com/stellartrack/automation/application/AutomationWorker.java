package com.stellartrack.automation.application;

import com.stellartrack.automation.config.AutomationProperties;
import com.stellartrack.automation.persistence.AutomationRunEntity;
import com.stellartrack.automation.persistence.AutomationRunRepository;
import com.stellartrack.common.web.CorrelationIds;
import java.net.InetAddress;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backend automation loop, independent of any browser. Only the instance holding the execution lease
 * evaluates; every other instance idles, so the same entry cannot be placed by two workers.
 */
@Component
public class AutomationWorker {

    private static final Logger log = LoggerFactory.getLogger(AutomationWorker.class);

    private final AutomationRunRepository runs;
    private final AutomationEvaluator evaluator;
    private final AutomationService automation;
    private final ExecutionLeaseService leases;
    private final AutomationProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String ownerId;

    public AutomationWorker(AutomationRunRepository runs, AutomationEvaluator evaluator, AutomationService automation,
            ExecutionLeaseService leases, AutomationProperties properties, Clock clock,
            PlatformTransactionManager txManager) {
        this.runs = runs;
        this.evaluator = evaluator;
        this.automation = automation;
        this.leases = leases;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.ownerId = hostName() + ":" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${stellartrack.automation.tick-interval}", initialDelay = 10_000)
    public void tick() {
        if (!properties.workerEnabled()) {
            return;
        }
        List<AutomationRunEntity> active = runs.findByStoppedAtIsNull();
        if (active.isEmpty() || !leases.tryAcquire(ExecutionLeaseService.AUTOMATION_LEASE, ownerId, properties.leaseTtl())) {
            return;
        }
        for (AutomationRunEntity run : active) {
            CorrelationIds.withCorrelationId("automation-" + run.getId(), () -> tickRun(run));
        }
    }

    private void tickRun(AutomationRunEntity run) {
        try {
            AutomationEvaluator.TickResult result = evaluator.tick(run, automation.session(run));
            tx.executeWithoutResult(s -> runs.findById(run.getId()).ifPresent(fresh -> {
                fresh.ticked(clock.instant(), result.pausedReason());
                for (int i = 0; i < result.submitted(); i++) {
                    fresh.entrySubmitted();
                }
            }));
            if (result.stopReason() != null) {
                automation.stop(run.getConfigurationId(), "Automatic stop: " + result.stopReason(), "system");
                evaluator.forgetRun(run.getId());
            }
        } catch (RuntimeException e) {
            log.error("Automation run {} failed", run.getId(), e);
            automation.markError(run.getConfigurationId(), "Evaluation error: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            evaluator.forgetRun(run.getId());
        }
    }

    public String ownerId() {
        return ownerId;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }
}
