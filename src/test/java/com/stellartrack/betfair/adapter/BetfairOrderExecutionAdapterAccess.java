package com.stellartrack.betfair.adapter;

import com.stellartrack.betfair.api.BettingTypes.PlaceExecutionReport;
import com.stellartrack.order.port.PlacementResult;

/** Test-only access to the package-private response mapping. */
public final class BetfairOrderExecutionAdapterAccess {

    private BetfairOrderExecutionAdapterAccess() {
    }

    public static PlacementResult toPlacementResult(PlaceExecutionReport report) {
        return BetfairOrderExecutionAdapter.toPlacementResult(report);
    }
}
