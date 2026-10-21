package com.stellartrack.dashboard.api;

import com.stellartrack.betfair.session.BetfairConnectionStatus;
import com.stellartrack.betfair.stream.StreamConnectionState;
import com.stellartrack.capital.application.CapitalService;
import com.stellartrack.capital.application.CapitalService.FundsView;
import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.domain.TradingMode;
import com.stellartrack.common.provider.ExchangeAvailability;
import com.stellartrack.configuration.application.ConfigurationService;
import com.stellartrack.order.application.OrderExposureQueries;
import com.stellartrack.order.control.TradingControlService;
import com.stellartrack.reporting.api.ReportFilterResolver;
import com.stellartrack.reporting.application.ReportSummaryService;
import com.stellartrack.reporting.application.ReportSummaryService.Summary;
import com.stellartrack.reporting.application.ReportTrendService;
import com.stellartrack.reporting.application.ReportTrendService.TrendSeries;
import com.stellartrack.reporting.domain.ReportFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account header and dashboard. KPI tiles reuse {@link ReportSummaryService} so they always match Reports.
 * Account-wide figures (funds, exposure) are labelled separately from this application's own bets.
 */
@RestController
@RequestMapping(ApiPaths.DASHBOARD)
@Tag(name = "Dashboard")
public class DashboardController {

    public record ConnectionView(boolean configured, String unavailableReason, BetfairConnectionStatus.Snapshot betfair,
            StreamConnectionState.Snapshot stream) {
    }

    public record AccountHeader(ConnectionView connection, Optional<FundsView> accountFunds,
            BigDecimal applicationOpenExposure, long runningConfigurations, boolean stopAllEngaged,
            TradingMode tradingMode) {
    }

    public record Dashboard(AccountHeader header, Summary summary, TrendSeries pnlTrend) {
    }

    private final ExchangeAvailability exchange;
    private final BetfairConnectionStatus betfairStatus;
    private final StreamConnectionState streamState;
    private final CapitalService capital;
    private final OrderExposureQueries exposure;
    private final ConfigurationService configurations;
    private final TradingControlService tradingControl;
    private final ReportFilterResolver filters;
    private final ReportSummaryService summaries;
    private final ReportTrendService trends;

    public DashboardController(ExchangeAvailability exchange, BetfairConnectionStatus betfairStatus,
            StreamConnectionState streamState, CapitalService capital, OrderExposureQueries exposure,
            ConfigurationService configurations, TradingControlService tradingControl, ReportFilterResolver filters,
            ReportSummaryService summaries, ReportTrendService trends) {
        this.exchange = exchange;
        this.betfairStatus = betfairStatus;
        this.streamState = streamState;
        this.capital = capital;
        this.exposure = exposure;
        this.configurations = configurations;
        this.tradingControl = tradingControl;
        this.filters = filters;
        this.summaries = summaries;
        this.trends = trends;
    }

    @GetMapping("/header")
    @Operation(summary = "Account header: connection, funds (account-wide), application exposure, running configurations")
    public AccountHeader header() {
        return new AccountHeader(
                new ConnectionView(exchange.isAvailable(), exchange.unavailableReason().orElse(null),
                        betfairStatus.snapshot(), streamState.snapshot()),
                capital.latest(),
                exposure.totalApplicationOpenExposure(),
                configurations.countRunning(),
                tradingControl.isStopAllEngaged(),
                tradingControl.tradingMode());
    }

    @GetMapping
    @Operation(summary = "Dashboard: header plus KPI tiles and P&L trend for the period (same definitions as Reports)")
    public Dashboard dashboard(@RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate, @RequestParam(required = false) String zone) {
        ReportFilter filter = filters.resolve(null, null, fromDate, toDate, zone, null, null, null, null);
        return new Dashboard(header(), summaries.summary(filter), trends.trends(filter, ReportTrendService.Granularity.DAY));
    }

}
