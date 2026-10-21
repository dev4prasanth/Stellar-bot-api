package com.stellartrack.market.filter;

/**
 * Runner/market filter fields with the verified source of each value. Betfair runner metadata keys are the
 * documented RUNNER_METADATA keys; they are populated only for some markets. Fields with source
 * {@link DataSource#UNAVAILABLE} have no verified provider yet and therefore can never pass an active rule.
 */
public enum FilterField {
    RUNNER_COUNT("Active runners", DataSource.BETFAIR_MARKET_BOOK, "numberOfActiveRunners"),
    PRICE_RANK("Favourite / price rank", DataSource.DERIVED_FROM_MARKET_BOOK, "rank of best back price among active runners"),
    ODDS("Odds", DataSource.BETFAIR_MARKET_BOOK, "best available price on the bet side"),
    RACE_TYPE("Race type", DataSource.BETFAIR_MARKET_DESCRIPTION, "raceType"),
    RACE_CLASSIFICATION("Race classification", DataSource.UNAVAILABLE, "no verified provider field"),
    AGE("Age", DataSource.BETFAIR_RUNNER_METADATA, "AGE"),
    WEIGHT("Weight", DataSource.BETFAIR_RUNNER_METADATA, "WEIGHT_VALUE"),
    CLOTH_NUMBER("Cloth number", DataSource.BETFAIR_RUNNER_METADATA, "CLOTH_NUMBER"),
    STALL_DRAW("Draw / stall", DataSource.BETFAIR_RUNNER_METADATA, "STALL_DRAW"),
    DAYS_SINCE_LAST_RUN("Days since last run", DataSource.BETFAIR_RUNNER_METADATA, "DAYS_SINCE_LAST_RUN"),
    FORM("Last-run form", DataSource.BETFAIR_RUNNER_METADATA, "FORM"),
    OFFICIAL_RATING("Official rating", DataSource.BETFAIR_RUNNER_METADATA, "OFFICIAL_RATING"),
    SEX("Horse sex", DataSource.BETFAIR_RUNNER_METADATA, "SEX_TYPE"),
    EQUIPMENT("Equipment", DataSource.BETFAIR_RUNNER_METADATA, "WEARING"),
    NUMBER_OF_PLACES("Number of places", DataSource.BETFAIR_MARKET_BOOK, "numberOfWinners"),
    MARKET_TRADED_VOLUME("Market traded volume", DataSource.BETFAIR_MARKET_BOOK, "totalMatched"),
    AVAILABLE_LIQUIDITY("Available liquidity at best price", DataSource.BETFAIR_MARKET_BOOK, "EX_BEST_OFFERS size");

    public enum DataSource {
        BETFAIR_MARKET_BOOK,
        BETFAIR_MARKET_DESCRIPTION,
        BETFAIR_RUNNER_METADATA,
        DERIVED_FROM_MARKET_BOOK,
        UNAVAILABLE
    }

    private final String label;
    private final DataSource source;
    private final String sourceField;

    FilterField(String label, DataSource source, String sourceField) {
        this.label = label;
        this.source = source;
        this.sourceField = sourceField;
    }

    public String label() {
        return label;
    }

    public DataSource source() {
        return source;
    }

    public String sourceField() {
        return sourceField;
    }
}
