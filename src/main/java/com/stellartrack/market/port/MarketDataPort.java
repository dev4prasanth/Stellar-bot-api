package com.stellartrack.market.port;

import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSearch;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.market.domain.RacingEvent;
import java.util.Collection;
import java.util.List;

/** Horse-racing market data, independent of the provider. Implemented by the Betfair adapter. */
public interface MarketDataPort {

    List<RacingEvent> listEvents(MarketSearch search);

    List<MarketSummary> listMarkets(MarketSearch search);

    /** Current prices/status. Implementations chunk requests to respect provider data-weight limits. */
    List<MarketPrices> getPrices(Collection<String> marketIds);
}
