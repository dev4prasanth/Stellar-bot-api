package com.stellartrack.reporting.application;

import com.stellartrack.common.exception.BusinessRuleException;
import com.stellartrack.reporting.domain.MetricDefinitions;
import com.stellartrack.reporting.domain.ReportFilter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Filtered CSV exports built from the same queries as the screen, so exported rows reconcile with charts. */
@Service
@Transactional(readOnly = true)
public class ReportExportService {

    static final int MAX_ROWS = 50_000;

    public enum Dataset {
        SETTLED,
        REJECTIONS,
        TRENDS,
        BREAKDOWN
    }

    private final OutcomeReportService outcomes;
    private final ReportTrendService trends;

    public ReportExportService(OutcomeReportService outcomes, ReportTrendService trends) {
        this.outcomes = outcomes;
        this.trends = trends;
    }

    public String export(Dataset dataset, ReportFilter filter, ReportTrendService.Granularity granularity, String classFilter) {
        CsvWriter csv = new CsvWriter();
        csv.row(List.of("# Stellar Track export", dataset.name(), "definitions " + MetricDefinitions.VERSION,
                "from " + filter.from(), "to " + filter.to(), "zone " + filter.zone().getId()));
        switch (dataset) {
            case SETTLED -> {
                csv.row(List.of("bet_id", "bet_status", "revision", "corrected", "outcome_class", "settled_at_utc", "mode",
                        "configuration", "market_id", "venue", "market_start_utc", "market_type", "selection_id", "runner",
                        "side", "requested_price", "price_matched", "price_reduced", "size_settled", "profit", "recommendation_id"));
                outcomes.allSettled(filter, classFilter, MAX_ROWS).forEach(r -> csv.row(java.util.Arrays.asList(r.betId(),
                        r.betStatus(), r.revision(), r.corrected(), r.outcomeClass(), r.settledAt(), r.mode(),
                        r.configurationName(), r.marketId(), r.venue(), r.marketStart(), r.marketType(), r.selectionId(),
                        r.runnerName(), r.side(), r.requestedPrice(), r.priceMatched(), r.priceReduced(), r.sizeSettled(),
                        r.profit(), r.recommendationId())));
            }
            case REJECTIONS -> {
                csv.row(List.of("recommendation_id", "created_at_utc", "rejected_at_utc", "reason", "configuration", "strategy",
                        "market_id", "venue", "market_start_utc", "market_type", "selection_id", "runner", "side",
                        "proposed_price", "proposed_stake", "outcome_classification", "runner_result", "dead_heat"));
                outcomes.allRejections(filter, classFilter, MAX_ROWS).forEach(r -> csv.row(java.util.Arrays.asList(
                        r.recommendationId(), r.createdAt(), r.rejectedAt(), r.rejectionReason(), r.configurationName(),
                        r.strategyCode(), r.marketId(), r.venue(), r.marketStart(), r.marketType(), r.selectionId(),
                        r.runnerName(), r.side(), r.proposedPrice(), r.proposedStake(),
                        r.outcomeClassification() == null ? "AWAITING_RESULT" : r.outcomeClassification(), r.runnerResult(),
                        r.deadHeat())));
            }
            case TRENDS -> {
                csv.row(List.of("bucket", "placed", "won", "lost", "manually_rejected", "gross_pnl", "commission", "net_pnl",
                        "cumulative_net_pnl"));
                trends.trends(filter, granularity).points().forEach(p -> csv.row(java.util.Arrays.asList(p.bucket(),
                        p.placed(), p.won(), p.lost(), p.manuallyRejected(), p.grossPnl(), p.commission(), p.netPnl(),
                        p.cumulativeNetPnl())));
            }
            case BREAKDOWN -> {
                csv.row(List.of("side", "market_type", "mode", "configuration", "odds_band", "units", "matched_stake",
                        "lay_liability", "profit"));
                outcomes.breakdown(filter).forEach(b -> csv.row(java.util.Arrays.asList(b.side(), b.marketType(), b.mode(),
                        b.configurationName(), b.oddsBand(), b.units(), b.matchedStake(), b.layLiability(), b.profit())));
            }
            default -> throw new BusinessRuleException("UNKNOWN_DATASET", "Unknown export dataset");
        }
        return csv.build();
    }
}
