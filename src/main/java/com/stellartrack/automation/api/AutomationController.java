package com.stellartrack.automation.api;

import com.stellartrack.auth.security.AdminPrincipal;
import com.stellartrack.automation.application.AutomationService;
import com.stellartrack.automation.application.ExecutionLeaseService;
import com.stellartrack.automation.persistence.AutomationRunEntity;
import com.stellartrack.automation.persistence.AutomationRunRepository;
import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.domain.TradingMode;
import com.stellartrack.configuration.application.ConfigurationService;
import com.stellartrack.configuration.domain.OperationalState;
import com.stellartrack.messaging.application.LayerFeedStatus;
import com.stellartrack.order.control.TradingControlService;
import com.stellartrack.order.control.TradingControlService.ControlState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.AUTOMATION)
@Tag(name = "Trading mode & automation")
public class AutomationController {

    public record ReasonRequest(@Size(max = 500) String reason) {
    }

    public record TradingModeRequest(@NotNull TradingMode mode) {
    }

    public record SwitchRequest(@NotNull UUID configurationId) {
    }

    public record LiveStopResult(List<UUID> stoppedConfigurations) {
    }

    public record ConfigurationRunState(UUID configurationId, String name, OperationalState state, String stateReason,
            Integer activeVersion, int currentVersion, List<String> startBlockers, RunView activeRun) {
    }

    public record RunView(UUID id, UUID configurationId, int configurationVersion, OperationalState state,
            String startedBy, Instant startedAt, Instant stoppedAt, String stopReason, int entriesSubmitted,
            Instant lastTickAt, String lastError) {

        static RunView from(AutomationRunEntity r) {
            return new RunView(r.getId(), r.getConfigurationId(), r.getConfigurationVersion(), r.getState(),
                    r.getStartedBy(), r.getStartedAt(), r.getStoppedAt(), r.getStopReason(), r.getEntriesSubmitted(),
                    r.getLastTickAt(), r.getLastError());
        }
    }

    public record AutomationStatus(ControlState control, List<ConfigurationRunState> configurations,
            List<LayerFeedStatus> layerFeeds, Map<String, Object> workerLease) {
    }

    private final AutomationService automation;
    private final ConfigurationService configurations;
    private final AutomationRunRepository runs;
    private final TradingControlService tradingControl;
    private final ExecutionLeaseService leases;

    public AutomationController(AutomationService automation, ConfigurationService configurations,
            AutomationRunRepository runs, TradingControlService tradingControl, ExecutionLeaseService leases) {
        this.automation = automation;
        this.configurations = configurations;
        this.runs = runs;
        this.tradingControl = tradingControl;
        this.leases = leases;
    }

    @GetMapping("/status")
    @Operation(summary = "Stop All state, per-configuration run state with start blockers, layer feed freshness")
    public AutomationStatus status() {
        List<ConfigurationRunState> states = configurations.list(false).stream().map(c -> new ConfigurationRunState(
                c.getId(), c.getName(), c.getOperationalState(), c.getStateReason(), c.getActiveVersion(),
                c.getCurrentVersion(),
                c.getOperationalState() == OperationalState.RUNNING ? List.of()
                        : automation.startBlockers(configurations.current(c.getId())),
                runs.findByConfigurationIdAndStoppedAtIsNull(c.getId()).map(RunView::from).orElse(null))).toList();
        return new AutomationStatus(tradingControl.state(), states, automation.feedStatuses(),
                leases.describe(ExecutionLeaseService.AUTOMATION_LEASE));
    }

    @PostMapping("/configurations/{id}/start")
    @Operation(summary = "Start automatic evaluation for a configuration (pins its current version)")
    public RunView start(@PathVariable UUID id) {
        return RunView.from(automation.start(id, AdminPrincipal.current().actorId()));
    }

    @PostMapping("/configurations/{id}/stop")
    public void stop(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest request) {
        automation.stop(id, request == null || request.reason() == null ? "Stopped by administrator" : request.reason(),
                AdminPrincipal.current().actorId());
    }

    @PostMapping("/live/stop")
    @Operation(summary = "Stop live trading in the current mode: the active configuration places no new bet and its pending "
            + "proposals are withdrawn. Bets already placed are NOT cancelled; they settle as normal.")
    public LiveStopResult stopLive(@Valid @RequestBody(required = false) ReasonRequest request) {
        return new LiveStopResult(automation.stopLive(request == null ? null : request.reason(),
                AdminPrincipal.current().actorId()));
    }

    @PostMapping("/live/switch")
    @Operation(summary = "Switch live trading to another configuration: stops the active one (as /live/stop) and starts the "
            + "target in one step. If the target cannot start (422 with blockers) nothing changes.")
    public RunView switchLive(@Valid @RequestBody SwitchRequest request) {
        return RunView.from(automation.switchTo(request.configurationId(), AdminPrincipal.current().actorId()));
    }

    @PostMapping("/stop-all")
    @Operation(summary = "Stop All: prevents new automatic entries and stops running configurations. Does NOT cancel or close existing orders.")
    public ControlState stopAll(@RequestBody(required = false) ReasonRequest request) {
        automation.stopAll(request == null ? null : request.reason(), AdminPrincipal.current().actorId());
        return tradingControl.state();
    }

    @PostMapping("/trading-mode")
    @Operation(summary = "Switch between the live trading modes. AUTOMATIC submits proposals without approval; MANUAL holds "
            + "every proposal for approval. Affects new proposals only. WATCH is refused here (409): it is started and "
            + "stopped with a Watch session. Refused (409) while a configuration runs: stop live trading first.")
    public ControlState changeTradingMode(@Valid @RequestBody TradingModeRequest request) {
        return automation.changeTradingMode(request.mode(), AdminPrincipal.current().actorId());
    }

    @PostMapping("/stop-all/release")
    public ControlState releaseStopAll() {
        return tradingControl.releaseStopAll(AdminPrincipal.current().actorId());
    }

    @GetMapping("/runs")
    public List<RunView> runs(@RequestParam(required = false) UUID configurationId,
            @RequestParam(defaultValue = "50") int limit) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 200));
        return (configurationId == null ? runs.findAllByOrderByStartedAtDesc(page)
                : runs.findByConfigurationIdOrderByStartedAtDesc(configurationId, page)).stream().map(RunView::from).toList();
    }
}
