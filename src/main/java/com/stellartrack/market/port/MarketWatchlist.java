package com.stellartrack.market.port;

import java.util.Set;

/** Markets the application needs live updates for (upcoming candidate markets and markets with open orders). */
public interface MarketWatchlist {

    Set<String> marketIdsToStream(int limit);
}
