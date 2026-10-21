package com.stellartrack.watch.api;

import com.stellartrack.auth.security.AdminPrincipal;
import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.api.PageRequests;
import com.stellartrack.common.api.PageResponse;
import com.stellartrack.common.domain.TradingMode;
import com.stellartrack.reporting.api.ReportController.FilterParams;
import com.stellartrack.reporting.api.ReportFilterResolver;
import com.stellartrack.reporting.application.ReportTrendService.Granularity;
import com.stellartrack.reporting.domain.ReportFilter;
import com.stellartrack.watch.application.WatchQueryService;
import com.stellartrack.watch.application.WatchQueryService.BetDetail;
import com.stellartrack.watch.application.WatchQueryService.BetFilter;
import com.stellartrack.watch.application.WatchQueryService.BetRow;
import com.stellartrack.watch.application.WatchQueryService.CapitalDetail;
import com.stellartrack.watch.application.WatchQueryService.Decision;
import com.stellartrack.watch.application.WatchQueryService.SessionConfiguration;
import com.stellartrack.watch.application.WatchQueryService.SessionDetail;
import com.stellartrack.watch.application.WatchQueryService.SessionSummary;
import com.stellartrack.watch.application.WatchQueryService.Status;
import com.stellartrack.watch.application.WatchReportService;
import com.stellartrack.watch.application.WatchReportService.Report;
import com.stellartrack.watch.application.WatchReportService.WatchReportFilter;
import com.stellartrack.watch.application.WatchSessionService;
import com.stellartrack.watch.application.WatchSessionService.StartCommand;
import com.stellartrack.watch.application.WatchSessionService.StartOptions;
import com.stellartrack.watch.domain.CapitalSource;
import com.stellartrack.watch.domain.WatchBetStatus;
import com.stellartrack.watch.persistence.WatchSessionEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.WATCH)
@Tag(name = "Watch Mode (simulation; never places a real bet)")
public class WatchController {

    public record StartRequest(@Size(max = 120) String name, @NotNull CapitalSource capitalSource,
            @DecimalMin("0.01") BigDecimal customAmount, @NotEmpty List<UUID> configurationIds,
            @Size(max = 1000) String notes) {
    }

    /** @param resumeMode the live mode to return to; MANUAL when omitted */
    public record StopRequest(TradingMode resumeMode, @Size(max = 500) String reason) {
    }

    private final WatchSessionService sessions;
    private final WatchQueryService queries;
    private final WatchReportService reports;
    private final ReportFilterResolver filters;
    private final Clock clock;

    public WatchController(WatchSessionService sessions, WatchQueryService queries, WatchReportService reports,
            ReportFilterResolver filters, Clock clock) {
        this.sessions = sessions;
        this.queries = queries;
        this.reports = reports;
        this.filters = filters;
        this.clock = clock;
    }

    @GetMapping("/status")
    @Operation(summary = "Current trading mode and the active Watch session (or the last one when none is active)")
    public Status status(@RequestParam(required = false) String zone) {
        return queries.status(zone(zone));
    }

    @GetMapping("/start-options")
    @Operation(summary = "Configurations that can be watched, the real available balance and pending approvals")
    public StartOptions startOptions() {
        return sessions.startOptions();
    }

    @PostMapping("/sessions")
    @Operation(summary = "Start Watch: switches the trading mode to WATCH (no real bets) and expires pending approvals")
    public SessionDetail start(@Valid @RequestBody StartRequest request) {
        WatchSessionEntity session = sessions.start(new StartCommand(request.name(), request.capitalSource(),
                request.customAmount(), request.configurationIds(), request.notes()), AdminPrincipal.current().actorId());
        return queries.session(session.getId(), zone(null));
    }

    @PostMapping("/sessions/{id}/stop")
    @Operation(summary = "Stop Watch and return to a live mode (Manual unless Automatic is chosen)")
    public SessionDetail stop(@PathVariable UUID id, @Valid @RequestBody(required = false) StopRequest request) {
        sessions.stop(id, request == null ? null : request.resumeMode(), request == null ? null : request.reason(),
                AdminPrincipal.current().actorId());
        return queries.session(id, zone(null));
    }

    @PostMapping("/sessions/{id}/configurations/{configurationId}/pause")
    public SessionConfiguration pause(@PathVariable UUID id, @PathVariable UUID configurationId) {
        return SessionConfiguration.from(sessions.pause(id, configurationId, AdminPrincipal.current().actorId()));
    }

    @PostMapping("/sessions/{id}/configurations/{configurationId}/resume")
    public SessionConfiguration resume(@PathVariable UUID id, @PathVariable UUID configurationId) {
        return SessionConfiguration.from(sessions.resume(id, configurationId, AdminPrincipal.current().actorId()));
    }

    @GetMapping("/sessions")
    public PageResponse<SessionSummary> sessionHistory(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String zone) {
        return queries.sessions(PageRequests.of(page, size, Sort.unsorted()), zone(zone));
    }

    @GetMapping("/sessions/{id}")
    public SessionDetail session(@PathVariable UUID id, @RequestParam(required = false) String zone) {
        return queries.session(id, zone(zone));
    }

    @GetMapping("/sessions/{id}/capital")
    @Operation(summary = "Virtual capital: starting, current, available, and the ledger of every change")
    public CapitalDetail capital(@PathVariable UUID id) {
        return queries.capital(id);
    }

    @GetMapping("/sessions/{id}/decisions")
    @Operation(summary = "Every evaluated runner, including no-bet and risk-rejected decisions with reasons")
    public PageResponse<Decision> decisions(@PathVariable UUID id, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return queries.decisions(id, PageRequests.of(page, size, Sort.unsorted()));
    }

    @GetMapping("/bets")
    public PageResponse<BetRow> bets(@RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false) WatchBetStatus status, @RequestParam(required = false) UUID configurationId,
            @RequestParam(required = false) String side, @RequestParam(required = false) String marketType,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return queries.bets(new BetFilter(sessionId, status, configurationId, side, marketType),
                PageRequests.of(page, size, Sort.unsorted()));
    }

    @PostMapping("/bets/{betId}/revoke")
    @Operation(summary = "Revoke the unmatched remainder of a simulated bet (it lapses; any matched part stands). Refused "
            + "(422) when it is fully matched, closed or settled.")
    public BetDetail revoke(@PathVariable UUID betId) {
        sessions.revokeBet(betId, AdminPrincipal.current().actorId());
        return queries.bet(betId);
    }

    @GetMapping("/bets/{betId}")
    @Operation(summary = "Simulated bet: decision snapshot, book at placement, fills and settlement evidence")
    public BetDetail bet(@PathVariable UUID betId) {
        return queries.bet(betId);
    }

    @GetMapping("/reports")
    @Operation(summary = "Watch reports: totals, P&L trend, breakdowns, staking and matching quality (simulated)")
    public Report report(@ModelAttribute FilterParams params, @RequestParam(required = false) UUID sessionId,
            @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return reports.report(filter(params, sessionId), granularity);
    }

    @GetMapping(value = "/reports/export", produces = "text/csv")
    @Operation(summary = "CSV of simulated bets in scope; every row is tagged SIMULATED")
    public ResponseEntity<String> export(@ModelAttribute FilterParams params, @RequestParam(required = false) UUID sessionId) {
        WatchReportFilter filter = filter(params, sessionId);
        String filename = "stellartrack-watch-" + LocalDate.now(filter.period().zone()) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv"))
                .body(reports.export(filter));
    }

    /** A session scope with no explicit dates covers the whole session. */
    private WatchReportFilter filter(FilterParams p, UUID sessionId) {
        boolean noDates = p.from() == null && p.to() == null && p.fromDate() == null && p.toDate() == null;
        if (sessionId != null && noDates) {
            WatchSessionEntity session = sessions.require(sessionId);
            ReportFilter base = filters.resolve(null, null, null, null, p.zone(), p.configurationId(), null, p.side(),
                    p.marketType());
            return new WatchReportFilter(sessionId, WatchReportService.sessionPeriod(base, session.getStartedAt(),
                    session.getStoppedAt(), clock.instant()));
        }
        return new WatchReportFilter(sessionId, filters.resolve(p.from(), p.to(), p.fromDate(), p.toDate(), p.zone(),
                p.configurationId(), null, p.side(), p.marketType()));
    }

    private ZoneId zone(String zone) {
        return filters.resolve(null, null, null, null, zone, null, null, null, null).zone();
    }
}
