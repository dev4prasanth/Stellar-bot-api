package com.stellartrack.market.domain;

import java.time.Instant;

/** A race meeting/event as reported by the market data provider. */
public record RacingEvent(String id, String name, String countryCode, String venue, String timezone, Instant openDate,
        int marketCount) {
}
