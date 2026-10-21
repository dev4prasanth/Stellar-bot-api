package com.stellartrack.betfair.adapter;

import com.stellartrack.betfair.api.BettingTypes.MarketCatalogue;
import com.stellartrack.betfair.api.BettingTypes.MarketFilter;
import com.stellartrack.betfair.api.BettingTypes.TimeRange;
import com.stellartrack.betfair.client.BetfairBettingClient;
import com.stellartrack.betfair.stream.MarketDataDelayIndicator;
import com.stellartrack.betfair.stream.MarketStreamCache;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSearch;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.market.domain.RacingEvent;
import com.stellartrack.market.port.MarketDataPort;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Betfair implementation of {@link MarketDataPort}. Prices come from the Exchange Stream cache when it holds a
 * fresh image for the market, otherwise from controlled listMarketBook polling, chunked to the weight limit.
 */
@Component
public class BetfairMarketDataAdapter implements MarketDataPort {

    /** Upper bound on catalogue pages per query, protecting the request budget. */
    private static final int MAX_CATALOGUE_PAGES = 10;

    private final BetfairBettingClient client;
    private final MarketStreamCache streamCache;
    private final MarketDataDelayIndicator delayIndicator;
    private final Clock clock;

    public BetfairMarketDataAdapter(BetfairBettingClient client, MarketStreamCache streamCache,
            MarketDataDelayIndicator delayIndicator, Clock clock) {
        this.client = client;
        this.streamCache = streamCache;
        this.delayIndicator = delayIndicator;
        this.clock = clock;
    }

    @Override
    public List<RacingEvent> listEvents(MarketSearch search) {
        return client.listEvents(filter(search, null)).stream()
                .filter(result -> result.event() != null)
                .map(result -> new RacingEvent(result.event().id(), result.event().name(), result.event().countryCode(),
                        result.event().venue(), result.event().timezone(), result.event().openDate(),
                        result.marketCount() == null ? 0 : result.marketCount()))
                .toList();
    }

    @Override
    public List<MarketSummary> listMarkets(MarketSearch search) {
        int pageSize = BetfairBettingClient.MAX_REQUEST_WEIGHT / BetfairBettingClient.CATALOGUE_WEIGHT_PER_MARKET;
        Map<String, MarketSummary> byId = new LinkedHashMap<>();
        Instant from = search.from();
        for (int page = 0; page < MAX_CATALOGUE_PAGES; page++) {
            List<MarketCatalogue> batch = client.listMarketCatalogue(filter(search, from), pageSize);
            batch.forEach(c -> BetfairMarketMapper.toSummary(c).ifPresent(s -> byId.putIfAbsent(s.marketId(), s)));
            if (batch.size() < pageSize) {
                break;
            }
            // Results are sorted FIRST_TO_START: continue from the last start time (duplicates removed by id).
            Instant lastStart = batch.getLast().marketStartTime();
            if (lastStart == null || lastStart.equals(from)) {
                break;
            }
            from = lastStart;
        }
        return new ArrayList<>(byId.values());
    }

    @Override
    public List<MarketPrices> getPrices(Collection<String> marketIds) {
        List<MarketPrices> result = new ArrayList<>();
        List<String> toPoll = new ArrayList<>();
        for (String marketId : Set.copyOf(marketIds)) {
            streamCache.freshPrices(marketId).ifPresentOrElse(result::add, () -> toPoll.add(marketId));
        }
        int chunk = BetfairBettingClient.MAX_REQUEST_WEIGHT / BetfairBettingClient.BOOK_WEIGHT_PER_MARKET;
        for (int i = 0; i < toPoll.size(); i += chunk) {
            List<String> ids = toPoll.subList(i, Math.min(toPoll.size(), i + chunk));
            Instant asOf = clock.instant();
            client.listMarketBook(ids).forEach(book -> {
                delayIndicator.observe(Boolean.TRUE.equals(book.isMarketDataDelayed()));
                result.add(BetfairMarketMapper.toPrices(book, asOf));
            });
        }
        return result;
    }

    private static MarketFilter filter(MarketSearch search, Instant fromOverride) {
        Instant from = fromOverride != null ? fromOverride : search.from();
        Set<String> types = search.marketTypes().stream().map(MarketType::name).collect(Collectors.toSet());
        return new MarketFilter(Set.of(BetfairBettingClient.HORSE_RACING_EVENT_TYPE_ID), null, search.marketIds(),
                search.countryCodes(), types, search.venues(),
                from == null && search.to() == null ? null : new TimeRange(from, search.to()),
                search.inPlayOnly());
    }
}
