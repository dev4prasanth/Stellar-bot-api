package com.stellartrack.market.application;

import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.common.provider.ExchangeAvailability;
import com.stellartrack.market.domain.MarketSearch;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.market.persistence.MarketEntity;
import com.stellartrack.market.persistence.MarketRepository;
import com.stellartrack.market.persistence.MarketRunnerEntity;
import com.stellartrack.market.persistence.MarketRunnerRepository;
import com.stellartrack.market.port.MarketDataPort;
import com.stellartrack.market.port.MarketWatchlist;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * Keeps a local copy of the horse-racing catalogue (markets and runners) so decisions, reports and post-race
 * correlation have stable references. The provider remains the source of truth; this is a cache with
 * explicit sync timestamps.
 */
@Service
public class MarketCatalogService implements MarketWatchlist {

    private static final Logger log = LoggerFactory.getLogger(MarketCatalogService.class);
    private static final Duration SYNC_HORIZON = Duration.ofHours(24);
    private static final Duration STREAM_HORIZON = Duration.ofHours(2);

    private final MarketDataPort marketData;
    private final MarketResultService results;
    private final MarketRepository markets;
    private final MarketRunnerRepository runners;
    private final ExchangeAvailability exchange;
    private final JsonCodec json;
    private final Clock clock;

    public MarketCatalogService(MarketDataPort marketData, MarketRepository markets, MarketRunnerRepository runners,
            MarketResultService results, ExchangeAvailability exchange, JsonCodec json, Clock clock) {
        this.marketData = marketData;
        this.results = results;
        this.markets = markets;
        this.runners = runners;
        this.exchange = exchange;
        this.json = json;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 15_000)
    public void syncUpcoming() {
        if (!exchange.isAvailable()) {
            return;
        }
        try {
            Instant now = clock.instant();
            List<MarketSummary> upcoming = marketData.listMarkets(MarketSearch.upcoming(now.minus(Duration.ofMinutes(30)),
                    now.plus(SYNC_HORIZON)));
            upsert(upcoming);
            // Current status (OPEN / SUSPENDED / in-play / CLOSED) from the same read-only market books used for prices.
            results.recordBooks(marketData.getPrices(upcoming.stream().map(MarketSummary::marketId).toList()));
            log.info("Catalogue sync stored {} horse-racing markets with current status", upcoming.size());
        } catch (RuntimeException e) {
            // Exception details only: provider errors and stack traces never contain credentials or tokens.
            log.warn("Catalogue sync failed: {}", e.toString(), e);
        }
    }

    @Transactional
    public void upsert(List<MarketSummary> summaries) {
        Instant now = clock.instant();
        for (MarketSummary summary : summaries) {
            MarketEntity market = markets.findById(summary.marketId()).orElseGet(() -> new MarketEntity(summary.marketId()));
            market.updateCatalogue(summary.eventId(), summary.eventName(), summary.venue(), summary.countryCode(),
                    summary.marketName(), summary.marketType(), summary.startTime(), summary.raceType(), now);
            if (summary.marketBaseRate() != null) {
                market.updateMarketBaseRate(summary.marketBaseRate());
            }
            if (summary.eachWayDivisor() != null) {
                market.updateEachWayDivisor(summary.eachWayDivisor());
            }
            markets.save(market);
            for (MarketSummary.RunnerInfo info : summary.runners()) {
                var key = new MarketRunnerEntity.Key(summary.marketId(), info.selectionId());
                MarketRunnerEntity runner = runners.findById(key).orElseGet(() -> new MarketRunnerEntity(key));
                runner.updateCatalogue(info.name(), info.sortPriority(), info.handicap(), json.write(info.metadata()), now);
                runners.save(runner);
            }
        }
    }

    /** Returns the cached catalogue for a market, fetching it from the provider when not yet known. */
    @Transactional
    public MarketSummary require(String marketId) {
        if (!markets.existsById(marketId)) {
            List<MarketSummary> fetched = marketData.listMarkets(MarketSearch.byIds(Set.of(marketId)));
            if (fetched.isEmpty()) {
                throw new NotFoundException("Horse-racing WIN/PLACE market", marketId);
            }
            upsert(fetched);
        }
        return toSummary(markets.findById(marketId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public MarketSummary toSummary(MarketEntity market) {
        List<MarketSummary.RunnerInfo> runnerInfos = runners.findByMarketId(market.getMarketId()).stream()
                .map(r -> new MarketSummary.RunnerInfo(r.getId().selectionId(), r.getRunnerName(), r.getHandicap(),
                        r.getSortPriority(), json.read(r.getMetadata(), new TypeReference<Map<String, String>>() { })))
                .toList();
        return new MarketSummary(market.getMarketId(), market.getMarketName(), market.getMarketType(),
                market.getMarketStartTime(), market.getEventId(), market.getEventName(), market.getVenue(),
                market.getCountryCode(), market.getRaceType(), null, runnerInfos, market.getMarketBaseRate(),
                market.getEachWayDivisor());
    }

    /** Cached, not-closed markets of the given type starting in [from, to], optionally restricted by country/venue. */
    @Transactional(readOnly = true)
    public List<MarketSummary> candidates(com.stellartrack.common.domain.MarketType type, Set<String> countries,
            Set<String> venues, Instant from, Instant to) {
        return markets.findByMarketStartTimeBetweenOrderByMarketStartTimeAsc(from, to).stream()
                .filter(m -> m.getMarketType() == type)
                .filter(m -> !"CLOSED".equals(m.getStatus()))
                .filter(m -> countries == null || countries.isEmpty() || countries.contains(m.getCountryCode()))
                .filter(m -> venues == null || venues.isEmpty() || venues.contains(m.getVenue()))
                .map(this::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> marketIdsToStream(int limit) {
        Instant now = clock.instant();
        return new LinkedHashSet<>(markets.findStreamCandidates(now.minus(Duration.ofMinutes(15)), now.plus(STREAM_HORIZON), limit));
    }
}
