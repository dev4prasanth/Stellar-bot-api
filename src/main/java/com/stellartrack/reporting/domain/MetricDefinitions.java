package com.stellartrack.reporting.domain;

import java.util.List;

/**
 * KPI glossary served to the UI (tooltips) and mirrored in docs/reporting/kpi-glossary.md. Version changes when
 * any definition changes so exported reports can state which definitions they used.
 */
public final class MetricDefinitions {

    public static final String VERSION = "2026.10-1";

    public record Definition(String key, String label, String grain, String dateBasis, String definition) {
    }

    public static final List<Definition> ALL = List.of(
            new Definition("evaluatedRecommendations", "Evaluated recommendations", "recommendation id",
                    "decision date (created_at)",
                    "Every evaluated runner decision (accepted, skipped/NO BET, pending, approved, rejected, expired),"
                            + " including administrator-selected race-market tickets."),
            new Definition("mode", "Mode (Manual / Automatic)", "order intent; recommendation", "not a date",
                    "Trading mode a bet or decision belongs to. Manual: confirmed by the administrator (approved"
                            + " proposal or race-market ticket, or a decision that required approval). Automatic: placed"
                            + " or decided without approval. Used by every mode filter, column and export."),
            new Definition("manuallyRejected", "Manually rejected proposals", "recommendation id",
                    "decision date", "Proposals the administrator rejected in the Manual-mode confirmation dialog. Never sent to Betfair."),
            new Definition("betsPlaced", "Bets placed", "order intent accepted by Betfair (betId)", "placement date",
                    "Distinct submissions Betfair accepted. Rejected proposals, risk refusals, exchange rejections"
                            + " and unknown outcomes are not counted."),
            new Definition("matched", "Matched bets", "placed bet with matched size > 0", "placement date",
                    "Placed bets with any matched amount; unmatched = placed with zero matched size."),
            new Definition("won", "Won", "settlement unit (bet id + bet status)", "settlement date",
                    "SETTLED units with positive Betfair profit (including WON_REDUCED, which is shown separately)."),
            new Definition("lost", "Lost", "settlement unit", "settlement date", "SETTLED units with negative Betfair profit."),
            new Definition("settledZero", "Settled at zero", "settlement unit", "settlement date",
                    "SETTLED units with zero profit; not counted as won or lost."),
            new Definition("void", "Void", "settlement unit", "settlement date", "Units Betfair voided."),
            new Definition("unresolved", "Unresolved", "placed bet", "placement date",
                    "Placed bets with matched size but no SETTLED/VOIDED settlement yet."),
            new Definition("exchangeRejected", "Exchange-rejected", "order intent", "placement date",
                    "Submissions Betfair refused (instruction/report FAILURE). Separate from administrator rejections."),
            new Definition("winRate", "Win rate", "settlement unit", "settlement date",
                    "(won + won reduced) / (won + won reduced + lost). Void, zero and unmatched units are excluded."),
            new Definition("rejectedFavorableRate", "Rejected selections later favorable", "rejected recommendation",
                    "decision date", "favorable / (favorable + unfavorable) among evaluated rejected proposals."
                            + " Hypothetical selection outcome only; never a won bet or realized P&L."),
            new Definition("grossPnl", "Gross P&L", "settlement unit", "settlement date",
                    "Sum of Betfair settled profit for this application's bets, before commission."),
            new Definition("commission", "Commission", "market", "market settlement date",
                    "Betfair market-level commission for markets in which this application had bets. Betfair reports"
                            + " commission per market only; it is never allocated to individual bets."),
            new Definition("netPnl", "Net P&L", "period", "settlement date", "Gross P&L minus market-level commission."));

    private MetricDefinitions() {
    }
}
