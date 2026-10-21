package com.stellartrack.order.port;

/** What happens to the unmatched portion at in-play turn (Betfair persistenceType). */
public enum PersistenceType {
    LAPSE,
    PERSIST,
    MARKET_ON_CLOSE
}
