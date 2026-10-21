package com.stellartrack.messaging.correlation;

import com.stellartrack.common.domain.MarketType;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.market.persistence.MarketEntity;
import com.stellartrack.market.persistence.MarketRepository;
import com.stellartrack.market.persistence.MarketRunnerEntity;
import com.stellartrack.market.persistence.MarketRunnerRepository;
import com.stellartrack.messaging.contract.PayloadFields;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * Correlates a layer race with the stored Betfair market catalogue: venue id → Betfair venue (configured), race
 * number → WIN market (configured rule), horse number → runner whose {@code CLOTH_NUMBER} metadata equals it.
 * Any missing step yields an explicit unmapped status instead of a guess.
 */
@Component
public class BetfairRaceCorrelator implements RaceMarketCorrelator {

    private static final String CLOTH_NUMBER = "CLOTH_NUMBER";

    private final BetfairMappingProperties properties;
    private final MarketRepository markets;
    private final MarketRunnerRepository runners;
    private final JsonCodec json;

    public BetfairRaceCorrelator(BetfairMappingProperties properties, MarketRepository markets,
            MarketRunnerRepository runners, JsonCodec json) {
        this.properties = properties;
        this.markets = markets;
        this.runners = runners;
        this.json = json;
    }

    @Override
    public Resolution resolve(String cardId, int raceNumber, Collection<Integer> horseNumbers) {
        Optional<String[]> parts = PayloadFields.splitCardId(cardId);
        BetfairMappingProperties.VenueMapping venue = parts.map(p -> properties.venue(p[0])).orElse(null);
        if (venue == null || venue.betfairVenue() == null || venue.betfairVenue().isBlank()) {
            return Resolution.unmapped(Status.VENUE_NOT_MAPPED, "No approved Betfair venue mapping for this venue id", null);
        }
        Optional<MarketEntity> market = findMarket(venue, LocalDate.parse(parts.get()[1]), raceNumber);
        if (market.isEmpty()) {
            return Resolution.unmapped(Status.MARKET_NOT_FOUND,
                    "No stored WIN market at " + venue.betfairVenue() + " matches race " + raceNumber, venue.betfairVenue());
        }
        Map<Integer, Long> byCloth = new HashMap<>();
        for (MarketRunnerEntity runner : runners.findByMarketId(market.get().getMarketId())) {
            clothNumber(runner).ifPresent(cloth -> byCloth.putIfAbsent(cloth, runner.getId().selectionId()));
        }
        if (byCloth.isEmpty()) {
            return new Resolution(Status.RUNNERS_NOT_MAPPED, "Stored runners carry no cloth numbers",
                    venue.betfairVenue(), market.get().getMarketId(), Map.of());
        }
        Map<Integer, Long> selected = new HashMap<>();
        horseNumbers.stream().filter(byCloth::containsKey).forEach(horse -> selected.put(horse, byCloth.get(horse)));
        boolean complete = selected.size() == horseNumbers.stream().distinct().count();
        return new Resolution(complete ? Status.MAPPED : Status.PARTIAL,
                complete ? "Mapped by venue, race market and cloth number" : "Some horse numbers have no matching runner",
                venue.betfairVenue(), market.get().getMarketId(), selected);
    }

    private Optional<MarketEntity> findMarket(BetfairMappingProperties.VenueMapping venue, LocalDate raceDate,
            int raceNumber) {
        ZoneId zone = ZoneId.of(venue.timeZone());
        List<MarketEntity> sameDay = markets.findByMarketStartTimeBetweenOrderByMarketStartTimeAsc(
                        raceDate.atStartOfDay(zone).toInstant(), raceDate.plusDays(1).atStartOfDay(zone).toInstant())
                .stream()
                .filter(m -> m.getMarketType() == MarketType.WIN && venue.betfairVenue().equalsIgnoreCase(m.getVenue()))
                .sorted(Comparator.comparing(MarketEntity::getMarketStartTime))
                .toList();
        return switch (venue.raceRule()) {
            case MARKET_NAME_PREFIX -> {
                Pattern prefix = Pattern.compile("^R" + raceNumber + "\\b", Pattern.CASE_INSENSITIVE);
                List<MarketEntity> matches = sameDay.stream()
                        .filter(m -> m.getMarketName() != null && prefix.matcher(m.getMarketName().trim()).find())
                        .toList();
                yield matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
            }
            case START_TIME_ORDER -> raceNumber >= 1 && raceNumber <= sameDay.size()
                    ? Optional.of(sameDay.get(raceNumber - 1)) : Optional.empty();
        };
    }

    private Optional<Integer> clothNumber(MarketRunnerEntity runner) {
        if (runner.getMetadata() == null) {
            return Optional.empty();
        }
        try {
            Map<String, String> metadata = json.read(runner.getMetadata(), new TypeReference<Map<String, String>>() { });
            String cloth = metadata == null ? null : metadata.get(CLOTH_NUMBER);
            return cloth == null || cloth.isBlank() ? Optional.empty() : Optional.of(Integer.parseInt(cloth.trim()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
