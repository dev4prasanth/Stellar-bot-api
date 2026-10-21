package com.stellartrack.market.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Dynamic market state at {@code asOf}. {@code dataDelayed} is the provider's own flag; delayed data must
 * never be presented as live.
 */
public record MarketPrices(
        String marketId,
        String status,
        boolean inPlay,
        boolean dataDelayed,
        Integer betDelaySeconds,
        Integer numberOfWinners,
        Integer numberOfActiveRunners,
        BigDecimal totalMatched,
        Long version,
        Instant asOf,
        PriceSource source,
        List<RunnerPrices> runners) {

    public enum PriceSource {
        STREAM,
        POLL
    }

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    public boolean isOpen() {
        return STATUS_OPEN.equals(status);
    }

    public Optional<RunnerPrices> runner(long selectionId) {
        return runners.stream().filter(r -> r.selectionId() == selectionId).findFirst();
    }

    public record RunnerPrices(
            long selectionId,
            String status,
            BigDecimal adjustmentFactor,
            BigDecimal lastPriceTraded,
            BigDecimal totalMatched,
            List<PriceLevel> availableToBack,
            List<PriceLevel> availableToLay,
            Instant removalDate) {

        public static final String ACTIVE = "ACTIVE";

        public boolean isActive() {
            return ACTIVE.equals(status);
        }

        public Optional<PriceLevel> bestBack() {
            return availableToBack == null || availableToBack.isEmpty() ? Optional.empty() : Optional.of(availableToBack.getFirst());
        }

        public Optional<PriceLevel> bestLay() {
            return availableToLay == null || availableToLay.isEmpty() ? Optional.empty() : Optional.of(availableToLay.getFirst());
        }
    }

    public record PriceLevel(BigDecimal price, BigDecimal size) {
    }
}
