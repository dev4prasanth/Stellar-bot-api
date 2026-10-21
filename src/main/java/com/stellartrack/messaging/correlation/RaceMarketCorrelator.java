package com.stellartrack.messaging.correlation;

import com.stellartrack.messaging.domain.NormalizedLayerRecord.MarketLink;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Resolves a layer race to its Betfair WIN market and runners using only approved mapping rules. */
public interface RaceMarketCorrelator {

    String MAPPING_RULE = "venue-map+race-market+cloth-number";

    enum Status {
        /** Market found and every requested horse number matched a runner cloth number. */
        MAPPED,
        /** Market found; some horse numbers have no runner with that cloth number. */
        PARTIAL,
        /** No approved venue mapping is configured for the card's venue. */
        VENUE_NOT_MAPPED,
        /** Venue mapped, but no stored WIN market matches the race on that day. */
        MARKET_NOT_FOUND,
        /** Market found, but its stored runners carry no cloth numbers. */
        RUNNERS_NOT_MAPPED
    }

    record Resolution(Status status, String reason, String betfairVenue, String marketId,
            Map<Integer, Long> selectionByHorse) {

        public Resolution {
            selectionByHorse = selectionByHorse == null ? Map.of() : Map.copyOf(selectionByHorse);
        }

        public static Resolution unmapped(Status status, String reason, String betfairVenue) {
            return new Resolution(status, reason, betfairVenue, null, Map.of());
        }
    }

    Resolution resolve(String cardId, int raceNumber, Collection<Integer> horseNumbers);

    /** Links for the horses that resolve; empty whenever the venue, market or runner mapping is missing. */
    default List<MarketLink> links(String cardId, int raceNumber, Collection<Integer> horseNumbers) {
        Resolution resolution = resolve(cardId, raceNumber, horseNumbers);
        if (resolution.marketId() == null) {
            return List.of();
        }
        return resolution.selectionByHorse().values().stream().distinct()
                .map(selectionId -> new MarketLink(resolution.marketId(), selectionId, MAPPING_RULE))
                .toList();
    }
}
