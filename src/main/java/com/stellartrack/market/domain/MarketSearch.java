package com.stellartrack.market.domain;

import com.stellartrack.common.domain.MarketType;
import java.time.Instant;
import java.util.Set;

/** Provider-neutral horse-racing market query. Empty sets mean "no restriction". */
public record MarketSearch(
        Set<String> marketIds,
        Instant from,
        Instant to,
        Set<String> countryCodes,
        Set<String> venues,
        Set<MarketType> marketTypes,
        Boolean inPlayOnly) {

    public MarketSearch {
        marketIds = marketIds == null ? Set.of() : Set.copyOf(marketIds);
        countryCodes = countryCodes == null ? Set.of() : Set.copyOf(countryCodes);
        venues = venues == null ? Set.of() : Set.copyOf(venues);
        marketTypes = marketTypes == null || marketTypes.isEmpty()
                ? Set.of(MarketType.WIN, MarketType.PLACE, MarketType.EACH_WAY) : Set.copyOf(marketTypes);
    }

    public static MarketSearch upcoming(Instant from, Instant to) {
        return new MarketSearch(null, from, to, null, null, null, null);
    }

    public static MarketSearch byIds(Set<String> marketIds) {
        return new MarketSearch(marketIds, null, null, null, null, null, null);
    }
}
