package com.stellartrack.reporting.api;

import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.api.PageRequests;
import com.stellartrack.common.api.PageResponse;
import com.stellartrack.reporting.application.BetCalculationService;
import com.stellartrack.reporting.application.BetCalculationService.BetCalculation;
import com.stellartrack.reporting.application.FactorAnalysisService;
import com.stellartrack.reporting.application.FactorAnalysisService.FactorAnalysis;
import com.stellartrack.reporting.application.OutcomeReportService;
import com.stellartrack.reporting.application.OutcomeReportService.BreakdownRow;
import com.stellartrack.reporting.application.OutcomeReportService.RejectionRow;
import com.stellartrack.reporting.application.OutcomeReportService.SettledRow;
import com.stellartrack.reporting.application.ReportExportService;
import com.stellartrack.reporting.application.ReportSummaryService;
import com.stellartrack.reporting.application.ReportSummaryService.Summary;
import com.stellartrack.reporting.application.ReportTrendService;
import com.stellartrack.reporting.application.ReportTrendService.FunnelStage;
import com.stellartrack.reporting.application.ReportTrendService.Granularity;
import com.stellartrack.reporting.application.ReportTrendService.TrendSeries;
import com.stellartrack.reporting.domain.MetricDefinitions;
import com.stellartrack.reporting.domain.ReportFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Application-defined reporting endpoints (not Betfair endpoints). All share one filter model. */
@RestController
@RequestMapping(ApiPaths.REPORTS)
@Tag(name = "Reports & Analytics")
public class ReportController {

    /** Common query parameters for every report endpoint. */
    public record FilterParams(Instant from, Instant to, LocalDate fromDate, LocalDate toDate, String zone,
            UUID configurationId, String mode, String side, String marketType) {
    }

    private final ReportFilterResolver resolver;
    private final ReportSummaryService summaries;
    private final ReportTrendService trends;
    private final OutcomeReportService outcomes;
    private final FactorAnalysisService factors;
    private final BetCalculationService calculations;
    private final ReportExportService exports;

    public ReportController(ReportFilterResolver resolver, ReportSummaryService summaries, ReportTrendService trends,
            OutcomeReportService outcomes, FactorAnalysisService factors, BetCalculationService calculations,
            ReportExportService exports) {
        this.resolver = resolver;
        this.summaries = summaries;
        this.trends = trends;
        this.outcomes = outcomes;
        this.factors = factors;
        this.calculations = calculations;
        this.exports = exports;
    }

    @GetMapping("/definitions")
    @Operation(summary = "KPI glossary: counting grain, date basis and definition of every metric")
    public Map<String, Object> definitions() {
        return Map.of("version", MetricDefinitions.VERSION, "definitions", MetricDefinitions.ALL);
    }

    @GetMapping("/summary")
    @Operation(summary = "KPI totals by time window and filters")
    public Summary summary(@ModelAttribute FilterParams params) {
        return summaries.summary(filter(params));
    }

    @GetMapping("/trends")
    @Operation(summary = "Dated chart series with explicit date basis per series")
    public TrendSeries trends(@ModelAttribute FilterParams params, @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return trends.trends(filter(params), granularity);
    }

    @GetMapping("/funnel")
    @Operation(summary = "Approval and execution funnel for recommendations created in the period (branched)")
    public List<FunnelStage> funnel(@ModelAttribute FilterParams params) {
        return trends.funnel(filter(params));
    }

    @GetMapping("/outcomes")
    @Operation(summary = "Settled units with classifications (source rows for outcome charts)")
    public PageResponse<SettledRow> outcomes(@ModelAttribute FilterParams params,
            @RequestParam(required = false) String outcomeClass, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        var p = PageRequests.of(page, size, Sort.unsorted());
        return outcomes.settled(filter(params), outcomeClass, p.getPageNumber(), p.getPageSize());
    }

    @GetMapping("/manual-rejections")
    @Operation(summary = "Rejected proposals with verified post-race selection outcomes (hypothetical, never P&L)")
    public PageResponse<RejectionRow> rejections(@ModelAttribute FilterParams params,
            @RequestParam(required = false) String classification, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        var p = PageRequests.of(page, size, Sort.unsorted());
        return outcomes.rejections(filter(params), classification, p.getPageNumber(), p.getPageSize());
    }

    @GetMapping("/manual-rejections/breakdown")
    public Map<String, Map<String, Long>> rejectionBreakdown(@ModelAttribute FilterParams params,
            @RequestParam(defaultValue = "reason") String dimension) {
        return outcomes.rejectionBreakdown(filter(params), dimension);
    }

    @GetMapping("/factor-analysis")
    @Operation(summary = "Decision-time factor distributions for settled wins versus losses")
    public FactorAnalysis factorAnalysis(@ModelAttribute FilterParams params, @RequestParam(required = false) String factor) {
        return factors.analyse(filter(params), factor);
    }

    @GetMapping("/stake-analysis")
    @Operation(summary = "Exposure and realized returns by side, market type, mode, configuration and odds band")
    public List<BreakdownRow> stakeAnalysis(@ModelAttribute FilterParams params) {
        return outcomes.breakdown(filter(params));
    }

    @GetMapping("/bets/{betId}/calculations")
    @Operation(summary = "Decision inputs, stake calculation, execution and authoritative settlement for one bet")
    public BetCalculation calculations(@PathVariable String betId) {
        return calculations.calculate(betId);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @Operation(summary = "Filtered CSV export consistent with the screen metrics")
    public ResponseEntity<String> export(@ModelAttribute FilterParams params,
            @RequestParam ReportExportService.Dataset dataset,
            @RequestParam(defaultValue = "DAY") Granularity granularity,
            @RequestParam(required = false) String classification) {
        ReportFilter filter = filter(params);
        String body = exports.export(dataset, filter, granularity, classification);
        String filename = "stellartrack-" + dataset.name().toLowerCase() + "-" + LocalDate.now(filter.zone()) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv"))
                .body(body);
    }

    private ReportFilter filter(FilterParams p) {
        return resolver.resolve(p.from(), p.to(), p.fromDate(), p.toDate(), p.zone(), p.configurationId(), p.mode(),
                p.side(), p.marketType());
    }
}
