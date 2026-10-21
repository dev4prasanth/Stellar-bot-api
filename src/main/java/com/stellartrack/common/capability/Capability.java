package com.stellartrack.common.capability;

/** Features whose availability depends on external contracts, credentials or approved business rules. */
public enum Capability {
    BETFAIR_SESSION("Betfair session"),
    BETFAIR_MARKET_DATA("Betfair market data"),
    BETFAIR_STREAM("Betfair Exchange Stream"),
    BETFAIR_ORDER_EXECUTION("Betfair order execution"),
    BETFAIR_ACCOUNT("Betfair account funds & statement"),
    LAYER1_INGESTION("Layer 1 prediction ingestion"),
    LAYER2_INGESTION("Layer 2 planetary-state ingestion"),
    LAYER3_INGESTION("Layer 3 prediction ingestion"),
    PREDICTION_STRATEGY("Prediction-based selection strategy"),
    CROSS_LAYER_CORRELATION("Layer 1/2/3 to Betfair correlation"),
    EXTERNAL_RACING_METADATA("Non-Betfair racing metadata provider"),
    DUTCHING("Dutching"),
    PRICE_MOVEMENT_DETECTION("Shortening/drifting price detection"),
    EQUALISATION("Equalisation"),
    CLOSE_OUT("Close-out"),
    FREE_BET_POSITIONING("Free-bet positioning"),
    PAYMENT_INITIATION("Deposit/withdrawal initiation"),
    REPLAY_EVALUATION("Historical Replay evaluation engine"),
    HYPOTHETICAL_MISSED_PROFIT("Hypothetical missed-profit estimate");

    private final String label;

    Capability(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
