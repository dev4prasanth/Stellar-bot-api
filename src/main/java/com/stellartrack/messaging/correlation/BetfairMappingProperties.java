package com.stellartrack.messaging.correlation;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Administrator-approved mapping from the layers' race identity ({@code venueId}, race date, race number, horse
 * number) to Betfair. Nothing is inferred: a venue without an entry here is reported as unmapped and produces no
 * market links. Example:
 * <pre>
 * stellartrack.messaging.betfair-mapping.venues:
 *   "[1096]": { betfair-venue: Kempton, time-zone: Europe/London, race-rule: MARKET_NAME_PREFIX }
 * </pre>
 */
@ConfigurationProperties(prefix = "stellartrack.messaging.betfair-mapping")
public record BetfairMappingProperties(Map<String, VenueMapping> venues) {

    public BetfairMappingProperties {
        venues = venues == null ? Map.of() : Map.copyOf(venues);
    }

    public VenueMapping venue(String venueId) {
        return venueId == null ? null : venues.get(venueId);
    }

    /**
     * @param betfairVenue the Betfair {@code event.venue} text the WIN markets carry
     * @param timeZone     zone in which the race date is a calendar day (defaults to Europe/London)
     * @param raceRule     how a race number selects the venue's WIN market on that day
     */
    public record VenueMapping(String betfairVenue, String timeZone, RaceRule raceRule) {

        public VenueMapping {
            timeZone = timeZone == null || timeZone.isBlank() ? "Europe/London" : timeZone;
            raceRule = raceRule == null ? RaceRule.MARKET_NAME_PREFIX : raceRule;
        }
    }

    public enum RaceRule {
        /** The WIN market whose name starts with {@code R<raceNumber>} (Betfair's AUS/US/ZA naming). */
        MARKET_NAME_PREFIX,
        /** The n-th WIN market of the day by start time; only for venues where the admin confirmed this holds. */
        START_TIME_ORDER
    }
}
