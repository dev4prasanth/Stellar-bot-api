package com.stellartrack.watch.domain;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.money.MoneyPolicy;
import com.stellartrack.common.money.PriceLadder;
import com.stellartrack.market.domain.MarketPrices;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Simulated matching, rule version {@value #VERSION}. Deliberately conservative, because Watch usually runs on
 * delayed market data and a simulated bet does not take part in the real queue:
 * <ul>
 *   <li>The limit price is the requested price made worse by {@code slippageTicks} ladder ticks (lower for BACK,
 *   higher for LAY), as an allowance for prices that may have moved since the data was published.</li>
 *   <li>A BACK bet matches against the offered back prices at or above the limit; a LAY bet against the offered lay
 *   prices at or below the limit, best price first, at the offered price (which can be better than requested).</li>
 *   <li>Each level fills at most the size shown, minus what earlier simulated fills of the same bet already took at
 *   that price, so the same displayed money is never matched twice.</li>
 * </ul>
 * Lapsing at the off, at close and on runner removal is decided by the caller from market state.
 */
public final class PaperFillEngine {

    public static final String VERSION = "FILL_V1";

    /** One simulated fill. */
    public record Fill(BigDecimal price, BigDecimal size) {
    }

    private PaperFillEngine() {
    }

    /** The requested price made worse for the bettor by {@code slippageTicks}. */
    public static BigDecimal limitPrice(BetSide side, BigDecimal requestedPrice, int slippageTicks) {
        if (slippageTicks <= 0) {
            return requestedPrice;
        }
        return PriceLadder.offset(requestedPrice, side == BetSide.BACK ? -slippageTicks : slippageTicks);
    }

    /**
     * Fills against the runner's offered prices.
     *
     * @param remaining   unmatched size of the bet
     * @param consumedAt  size already filled per price by earlier simulated fills of this bet (may be empty)
     * @return fills in matching order; empty when nothing is available at or better than the limit
     */
    public static List<Fill> match(BetSide side, BigDecimal limitPrice, BigDecimal remaining,
            MarketPrices.RunnerPrices runner, Map<BigDecimal, BigDecimal> consumedAt) {
        List<Fill> fills = new ArrayList<>();
        if (runner == null || !runner.isActive() || remaining == null || remaining.signum() <= 0) {
            return fills;
        }
        List<MarketPrices.PriceLevel> levels = new ArrayList<>(side == BetSide.BACK
                ? nullSafe(runner.availableToBack()) : nullSafe(runner.availableToLay()));
        // Best price for the bettor first: highest back price, lowest lay price.
        levels.sort(side == BetSide.BACK
                ? Comparator.comparing(MarketPrices.PriceLevel::price).reversed()
                : Comparator.comparing(MarketPrices.PriceLevel::price));
        BigDecimal left = remaining;
        for (MarketPrices.PriceLevel level : levels) {
            if (left.signum() <= 0) {
                break;
            }
            boolean acceptable = side == BetSide.BACK
                    ? level.price().compareTo(limitPrice) >= 0
                    : level.price().compareTo(limitPrice) <= 0;
            if (!acceptable) {
                break;
            }
            BigDecimal alreadyTaken = consumedAt == null ? BigDecimal.ZERO : consumedFor(consumedAt, level.price());
            BigDecimal available = MoneyPolicy.stake(level.size().subtract(alreadyTaken).max(BigDecimal.ZERO));
            BigDecimal size = available.min(left);
            if (size.signum() > 0) {
                fills.add(new Fill(level.price().setScale(2, RoundingMode.UNNECESSARY), size));
                left = left.subtract(size);
            }
        }
        return fills;
    }

    /** Size-weighted average price of a set of fills (4 decimals), or null when there are none. */
    public static BigDecimal averagePrice(List<Fill> fills) {
        BigDecimal size = BigDecimal.ZERO;
        BigDecimal weighted = BigDecimal.ZERO;
        for (Fill fill : fills) {
            size = size.add(fill.size());
            weighted = weighted.add(fill.price().multiply(fill.size()));
        }
        return size.signum() == 0 ? null : weighted.divide(size, 4, MoneyPolicy.AMOUNT_ROUNDING);
    }

    private static BigDecimal consumedFor(Map<BigDecimal, BigDecimal> consumedAt, BigDecimal price) {
        return consumedAt.entrySet().stream().filter(e -> e.getKey().compareTo(price) == 0)
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<MarketPrices.PriceLevel> nullSafe(List<MarketPrices.PriceLevel> levels) {
        return levels == null ? List.of() : levels;
    }
}
