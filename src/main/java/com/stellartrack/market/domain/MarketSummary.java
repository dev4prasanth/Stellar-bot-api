package com.stellartrack.market.domain;

import com.stellartrack.common.domain.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Static market description (catalogue). {@code raceType} and runner metadata are only present when the
 * provider supplies them; absent values stay null/missing and are never defaulted.
 *
 * @param marketBaseRate the market's commission base rate in percent (Betfair market description), when known
 * @param eachWayDivisor EACH_WAY markets: the place part pays at 1/divisor of the win odds (market description)
 */
public record MarketSummary(
        String marketId,
        String marketName,
        MarketType marketType,
        Instant startTime,
        String eventId,
        String eventName,
        String venue,
        String countryCode,
        String raceType,
        BigDecimal totalMatched,
        List<RunnerInfo> runners,
        BigDecimal marketBaseRate,
        BigDecimal eachWayDivisor) {

    public MarketSummary(String marketId, String marketName, MarketType marketType, Instant startTime, String eventId,
            String eventName, String venue, String countryCode, String raceType, BigDecimal totalMatched,
            List<RunnerInfo> runners, BigDecimal marketBaseRate) {
        this(marketId, marketName, marketType, startTime, eventId, eventName, venue, countryCode, raceType, totalMatched,
                runners, marketBaseRate, null);
    }

    public MarketSummary(String marketId, String marketName, MarketType marketType, Instant startTime, String eventId,
            String eventName, String venue, String countryCode, String raceType, BigDecimal totalMatched,
            List<RunnerInfo> runners) {
        this(marketId, marketName, marketType, startTime, eventId, eventName, venue, countryCode, raceType, totalMatched,
                runners, null);
    }

    /**
     * @param metadata provider runner metadata; Betfair sends keys with null values for data it does not have, and
     *                 those are dropped so missing data stays missing (never defaulted)
     */
    public record RunnerInfo(long selectionId, String name, BigDecimal handicap, Integer sortPriority,
            Map<String, String> metadata) {
        public RunnerInfo {
            metadata = metadata == null ? Map.of() : metadata.entrySet().stream()
                    .filter(e -> e.getKey() != null && e.getValue() != null)
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }
}
