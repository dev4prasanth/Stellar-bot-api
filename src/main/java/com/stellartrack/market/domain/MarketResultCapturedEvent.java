package com.stellartrack.market.domain;

/** Published after official runner results for a closed market have been stored. */
public record MarketResultCapturedEvent(String marketId) {
}
