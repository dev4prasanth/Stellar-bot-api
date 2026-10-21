package com.stellartrack.common.domain;

import java.util.Optional;

/**
 * Horse-racing market types supported by the application (Betfair market type codes WIN, PLACE and EACH_WAY).
 * An EACH_WAY bet is one bet covering a win part and a place part of equal stake; the place part pays at
 * 1/{@code eachWayDivisor} of the win odds for the market's number of places. Each-Way is supported for BACK bets only.
 */
public enum MarketType {
    WIN,
    PLACE,
    EACH_WAY;

    public static Optional<MarketType> fromBetfairCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return switch (code) {
            case "WIN" -> Optional.of(WIN);
            case "PLACE" -> Optional.of(PLACE);
            case "EACH_WAY" -> Optional.of(EACH_WAY);
            default -> Optional.empty();
        };
    }
}
