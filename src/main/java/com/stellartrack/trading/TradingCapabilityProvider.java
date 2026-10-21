package com.stellartrack.trading;

import com.stellartrack.common.capability.Capability;
import com.stellartrack.common.capability.CapabilityProvider;
import com.stellartrack.common.capability.CapabilityReport;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Advanced trading features stay blocked until each one is fully specified (supported market/side combinations,
 * formula, trigger, price and liquidity requirements, order linkage, partial-fill handling, failure policy and
 * residual exposure). No invented live rule is activated. Order infrastructure already supports linked
 * HEDGE/CLOSE_OUT intents for when the specifications are approved.
 */
@Component
public class TradingCapabilityProvider implements CapabilityProvider {

    private static final String[] SPEC = {
            "Supported market/side combinations", "Formula", "Trigger", "Price and liquidity requirements",
            "Order linkage", "Partial-fill handling", "Failure policy", "Residual exposure treatment"};

    @Override
    public List<CapabilityReport> capabilities() {
        return List.of(
                CapabilityReport.blocked(Capability.DUTCHING,
                        "Back Dutch bets on Win markets can be placed from Trading → Race markets. Automatic Dutching by "
                                + "configurations waits for an approved selection strategy", SPEC),
                CapabilityReport.blocked(Capability.PRICE_MOVEMENT_DETECTION,
                        "Shortening/drifting thresholds and windows not yet specified", SPEC),
                CapabilityReport.blocked(Capability.EQUALISATION, "Equalisation formula and trigger not yet specified", SPEC),
                CapabilityReport.blocked(Capability.CLOSE_OUT, "Close-out trigger and pricing rules not yet specified", SPEC),
                CapabilityReport.blocked(Capability.FREE_BET_POSITIONING,
                        "Free-bet positioning is not precisely defined", SPEC),
                CapabilityReport.blocked(Capability.EXTERNAL_RACING_METADATA,
                        "Fields not supplied by Betfair (e.g. race classification) need an approved data provider",
                        "Approved racing data provider and field mapping"),
                CapabilityReport.blocked(Capability.HYPOTHETICAL_MISSED_PROFIT,
                        "A hypothetical settlement model for rejected proposals has not been approved",
                        "Approved settlement and commission assumptions",
                        "Confirmation that a hypothetical estimate is wanted"));
    }
}
