package com.stellartrack.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Betfair Exchange odds ladder (decimal odds between 1.01 and 1000) and its tick increments,
 * as documented in the Betfair Exchange API "Betfair Price Increments" reference.
 */
public final class PriceLadder {

    public static final BigDecimal MIN_PRICE = new BigDecimal("1.01");
    public static final BigDecimal MAX_PRICE = new BigDecimal("1000");

    private record Band(BigDecimal from, BigDecimal to, BigDecimal increment) {
    }

    private static final List<Band> BANDS = List.of(
            new Band(new BigDecimal("1.01"), new BigDecimal("2"), new BigDecimal("0.01")),
            new Band(new BigDecimal("2"), new BigDecimal("3"), new BigDecimal("0.02")),
            new Band(new BigDecimal("3"), new BigDecimal("4"), new BigDecimal("0.05")),
            new Band(new BigDecimal("4"), new BigDecimal("6"), new BigDecimal("0.1")),
            new Band(new BigDecimal("6"), new BigDecimal("10"), new BigDecimal("0.2")),
            new Band(new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("0.5")),
            new Band(new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("1")),
            new Band(new BigDecimal("30"), new BigDecimal("50"), new BigDecimal("2")),
            new Band(new BigDecimal("50"), new BigDecimal("100"), new BigDecimal("5")),
            new Band(new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("10")));

    private PriceLadder() {
    }

    public static boolean isValid(BigDecimal price) {
        if (price == null || price.compareTo(MIN_PRICE) < 0 || price.compareTo(MAX_PRICE) > 0) {
            return false;
        }
        Band band = bandContaining(price, true);
        return price.subtract(band.from()).remainder(band.increment()).signum() == 0;
    }

    /**
     * Rounds a price onto the ladder. {@code towardsLower} rounds to the lower tick (a better price for a
     * LAY requester, a worse one for a BACK requester); callers choose the direction that is safe for the side.
     */
    public static BigDecimal roundToTick(BigDecimal price, boolean towardsLower) {
        BigDecimal clamped = price.max(MIN_PRICE).min(MAX_PRICE);
        Band band = bandContaining(clamped, towardsLower);
        BigDecimal steps = clamped.subtract(band.from())
                .divide(band.increment(), 0, towardsLower ? RoundingMode.FLOOR : RoundingMode.CEILING);
        return band.from().add(steps.multiply(band.increment())).stripTrailingZeros().setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Moves a valid ladder price by {@code ticks} (negative moves towards shorter odds). */
    public static BigDecimal offset(BigDecimal price, int ticks) {
        if (!isValid(price)) {
            throw new IllegalArgumentException("Price is not on the Betfair ladder: " + price);
        }
        BigDecimal current = price;
        for (int i = 0; i < Math.abs(ticks); i++) {
            boolean up = ticks > 0;
            if (up && current.compareTo(MAX_PRICE) >= 0 || !up && current.compareTo(MIN_PRICE) <= 0) {
                break;
            }
            // At a band boundary, moving up uses the upper band's increment and moving down the lower band's.
            Band band = bandContaining(current, !up);
            current = up ? current.add(band.increment()) : current.subtract(band.increment());
        }
        return current.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Number of ladder ticks from {@code from} to {@code to} (negative when {@code to} is shorter). */
    public static int ticksBetween(BigDecimal from, BigDecimal to) {
        BigDecimal a = roundToTick(from, true);
        BigDecimal b = roundToTick(to, true);
        int direction = b.compareTo(a);
        int ticks = 0;
        BigDecimal current = a;
        while (current.compareTo(b) != 0 && ticks < 400) {
            current = offset(current, direction);
            ticks++;
        }
        return direction * ticks;
    }

    /**
     * Band lookup. Boundary prices (e.g. 2.00) belong to both adjacent bands; when moving/rounding upward the
     * higher band applies, when moving downward the lower band applies.
     */
    private static Band bandContaining(BigDecimal price, boolean preferLowerBandAtBoundary) {
        for (Band band : BANDS) {
            boolean insideOpen = price.compareTo(band.from()) > 0 && price.compareTo(band.to()) < 0;
            boolean atFrom = price.compareTo(band.from()) == 0;
            boolean atTo = price.compareTo(band.to()) == 0;
            if (insideOpen || (atFrom && !preferLowerBandAtBoundary) || (atTo && preferLowerBandAtBoundary)) {
                return band;
            }
        }
        // MIN_PRICE when preferring lower, MAX_PRICE when preferring upper.
        return preferLowerBandAtBoundary ? BANDS.getFirst() : BANDS.getLast();
    }
}
