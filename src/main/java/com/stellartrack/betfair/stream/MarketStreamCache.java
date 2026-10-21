package com.stellartrack.betfair.stream;

import com.stellartrack.betfair.config.BetfairProperties;
import com.stellartrack.market.domain.MarketPrices;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Market cache built from Exchange Stream {@code mcm} messages. {@code img=true} replaces the market image;
 * otherwise changes are applied as deltas. Level-based ladders ({@code batb}/{@code batl}) are
 * {@code [level, price, size]} where size 0 removes the level.
 */
@Component
public class MarketStreamCache {

    private static final class RunnerState {
        final Map<Integer, MarketPrices.PriceLevel> back = new TreeMap<>();
        final Map<Integer, MarketPrices.PriceLevel> lay = new TreeMap<>();
        BigDecimal lastTraded;
        BigDecimal tradedVolume;
        String status = MarketPrices.RunnerPrices.ACTIVE;
        BigDecimal adjustmentFactor;
        java.time.Instant removalDate;
    }

    private static final class MarketState {
        final Map<Long, RunnerState> runners = new ConcurrentHashMap<>();
        String status;
        boolean inPlay;
        Integer betDelay;
        Integer numberOfWinners;
        Integer numberOfActiveRunners;
        Long version;
        BigDecimal totalMatched;
    }

    private final Map<String, MarketState> markets = new ConcurrentHashMap<>();
    private final StreamConnectionState connection;
    private final MarketDataDelayIndicator delayIndicator;
    private final Duration maxSilence;

    public MarketStreamCache(StreamConnectionState connection, MarketDataDelayIndicator delayIndicator,
            BetfairProperties properties) {
        this.connection = connection;
        this.delayIndicator = delayIndicator;
        Duration heartbeat = properties.stream() != null ? properties.stream().heartbeat() : Duration.ofSeconds(5);
        this.maxSilence = heartbeat.multipliedBy(3);
    }

    public void clear() {
        markets.clear();
    }

    public void retainOnly(java.util.Set<String> marketIds) {
        markets.keySet().retainAll(marketIds);
    }

    /** Applies one {@code mc} element. */
    public void apply(JsonNode change) {
        String marketId = change.path("id").asString();
        MarketState market = change.path("img").asBoolean(false)
                ? replace(marketId)
                : markets.computeIfAbsent(marketId, id -> new MarketState());
        synchronized (market) {
            if (change.has("tv")) {
                market.totalMatched = change.path("tv").decimalValue();
            }
            JsonNode definition = change.path("marketDefinition");
            if (!definition.isMissingNode()) {
                applyDefinition(market, definition);
            }
            for (JsonNode rc : change.path("rc")) {
                RunnerState runner = market.runners.computeIfAbsent(rc.path("id").asLong(), id -> new RunnerState());
                applyLadder(runner.back, rc.path("batb"));
                applyLadder(runner.lay, rc.path("batl"));
                if (rc.has("ltp")) {
                    runner.lastTraded = rc.path("ltp").decimalValue();
                }
                if (rc.has("tv")) {
                    runner.tradedVolume = rc.path("tv").decimalValue();
                }
            }
        }
    }

    private MarketState replace(String marketId) {
        MarketState fresh = new MarketState();
        markets.put(marketId, fresh);
        return fresh;
    }

    private static void applyDefinition(MarketState market, JsonNode definition) {
        market.status = definition.path("status").asString(market.status);
        market.inPlay = definition.path("inPlay").asBoolean(market.inPlay);
        if (definition.has("betDelay")) {
            market.betDelay = definition.path("betDelay").asInt();
        }
        if (definition.has("numberOfWinners")) {
            market.numberOfWinners = definition.path("numberOfWinners").asInt();
        }
        if (definition.has("numberOfActiveRunners")) {
            market.numberOfActiveRunners = definition.path("numberOfActiveRunners").asInt();
        }
        if (definition.has("version")) {
            market.version = definition.path("version").asLong();
        }
        for (JsonNode rd : definition.path("runners")) {
            RunnerState runner = market.runners.computeIfAbsent(rd.path("id").asLong(), id -> new RunnerState());
            runner.status = rd.path("status").asString(runner.status);
            if (rd.has("adjustmentFactor")) {
                runner.adjustmentFactor = rd.path("adjustmentFactor").decimalValue();
            }
            if (rd.has("removalDate")) {
                runner.removalDate = java.time.Instant.parse(rd.path("removalDate").asString());
            }
        }
    }

    private static void applyLadder(Map<Integer, MarketPrices.PriceLevel> ladder, JsonNode updates) {
        for (JsonNode level : updates) {
            int index = level.get(0).asInt();
            BigDecimal price = level.get(1).decimalValue();
            BigDecimal size = level.get(2).decimalValue();
            if (size.signum() == 0) {
                ladder.remove(index);
            } else {
                ladder.put(index, new MarketPrices.PriceLevel(price, size));
            }
        }
    }

    /** Prices from the stream, only while the connection is demonstrably fresh and an image exists. */
    public Optional<MarketPrices> freshPrices(String marketId) {
        if (!connection.isFresh(maxSilence)) {
            return Optional.empty();
        }
        MarketState market = markets.get(marketId);
        if (market == null || market.status == null) {
            return Optional.empty();
        }
        synchronized (market) {
            List<MarketPrices.RunnerPrices> runners = new ArrayList<>();
            market.runners.forEach((selectionId, r) -> runners.add(new MarketPrices.RunnerPrices(selectionId, r.status,
                    r.adjustmentFactor, r.lastTraded, r.tradedVolume, List.copyOf(r.back.values()),
                    List.copyOf(r.lay.values()), r.removalDate)));
            return Optional.of(new MarketPrices(marketId, market.status, market.inPlay, delayIndicator.assumeDelayed(), market.betDelay,
                    market.numberOfWinners, market.numberOfActiveRunners, market.totalMatched, market.version,
                    connection.snapshot().lastMessageAt(), MarketPrices.PriceSource.STREAM, runners));
        }
    }
}
