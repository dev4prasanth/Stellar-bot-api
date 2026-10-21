package com.stellartrack.support;

import com.stellartrack.common.domain.MarketType;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** TEST-ONLY market fixtures. */
public final class MarketFixtures {

    private MarketFixtures() {
    }

    public static String newMarketId() {
        return "1." + ThreadLocalRandom.current().nextInt(100_000_000, 999_999_999);
    }

    public static MarketSummary market(String marketId, Instant start, MarketType type) {
        return new MarketSummary(marketId, "2m4f Hcap", type, start, "ev-" + marketId, "Test Park", "Test Park", "GB",
                "Flat", null, List.of(
                        new MarketSummary.RunnerInfo(101, "Favourite", BigDecimal.ZERO, 1, Map.of("AGE", "5")),
                        new MarketSummary.RunnerInfo(102, "Outsider", BigDecimal.ZERO, 2, Map.of("AGE", "7"))));
    }

    /** An EACH_WAY market paying 1/{@code divisor} of the win odds for a place. */
    public static MarketSummary eachWayMarket(String marketId, Instant start, String divisor) {
        MarketSummary win = market(marketId, start, MarketType.EACH_WAY);
        return new MarketSummary(win.marketId(), win.marketName(), MarketType.EACH_WAY, start, win.eventId(),
                win.eventName(), win.venue(), win.countryCode(), win.raceType(), null, win.runners(), null,
                new BigDecimal(divisor));
    }

    public static MarketPrices open(String marketId, String favBack, String favLay) {
        return prices(marketId, "OPEN", "ACTIVE", "ACTIVE", favBack, favLay);
    }

    public static MarketPrices closed(String marketId, String favouriteStatus, String outsiderStatus) {
        return prices(marketId, "CLOSED", favouriteStatus, outsiderStatus, null, null);
    }

    private static MarketPrices prices(String marketId, String status, String favStatus, String outStatus, String back, String lay) {
        return new MarketPrices(marketId, status, false, false, 0, 1, 2, new BigDecimal("25000"), 4L, Instant.now(),
                MarketPrices.PriceSource.POLL, List.of(
                        new MarketPrices.RunnerPrices(101, favStatus, null, null, null,
                                back == null ? List.of() : List.of(new MarketPrices.PriceLevel(new BigDecimal(back), new BigDecimal("200"))),
                                lay == null ? List.of() : List.of(new MarketPrices.PriceLevel(new BigDecimal(lay), new BigDecimal("200"))),
                                null),
                        new MarketPrices.RunnerPrices(102, outStatus, null, null, null,
                                back == null ? List.of() : List.of(new MarketPrices.PriceLevel(new BigDecimal("12.0"), new BigDecimal("50"))),
                                lay == null ? List.of() : List.of(new MarketPrices.PriceLevel(new BigDecimal("13.0"), new BigDecimal("50"))),
                                null)));
    }
}
