package com.stellartrack.staking.domain;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.money.MoneyPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Base-case exchange arithmetic for a fully matched, unadjusted bet at decimal odds {@code p} and stake {@code s},
 * before commission:
 * <ul>
 *   <li>BACK gross profit on a win: {@code s × (p − 1)}; amount at risk: {@code s}.</li>
 *   <li>LAY liability: {@code s × (p − 1)}; LAY gross profit when the selection loses: {@code s}.</li>
 * </ul>
 * Actual results always come from provider settlement records (partial matches, reductions, dead heats, voids).
 */
public final class BetMath {

    private BetMath() {
    }

    public static BigDecimal backWinProfit(BigDecimal stake, BigDecimal price) {
        return MoneyPolicy.amount(stake.multiply(price.subtract(BigDecimal.ONE)));
    }

    public static BigDecimal layLiability(BigDecimal stake, BigDecimal price) {
        return MoneyPolicy.amount(stake.multiply(price.subtract(BigDecimal.ONE)));
    }

    /** Money at risk if the bet loses: the stake for BACK, the liability for LAY. */
    public static BigDecimal exposure(BetSide side, BigDecimal stake, BigDecimal price) {
        return side == BetSide.BACK ? MoneyPolicy.amount(stake) : layLiability(stake, price);
    }

    /** Gross profit if the bet wins: {@code s(p−1)} for BACK, {@code s} for LAY. */
    public static BigDecimal grossProfitIfWins(BetSide side, BigDecimal stake, BigDecimal price) {
        return side == BetSide.BACK ? backWinProfit(stake, price) : MoneyPolicy.amount(stake);
    }

    // ---- Each-Way (BACK only). The stake is per part: one win part and one place part of equal stake.

    /** Odds of the place part: {@code 1 + (p − 1) / d} for place terms of 1/d of the win odds. */
    public static BigDecimal eachWayPlacePrice(BigDecimal price, BigDecimal divisor) {
        return BigDecimal.ONE.add(price.subtract(BigDecimal.ONE)
                .divide(divisor, MoneyPolicy.CALCULATION_SCALE, java.math.RoundingMode.HALF_EVEN));
    }

    /** Total stake of an Each-Way bet: {@code 2s} (win part + place part). */
    public static BigDecimal eachWayTotalStake(BigDecimal stake) {
        return MoneyPolicy.amount(stake.multiply(BigDecimal.valueOf(2)));
    }

    /** Gross profit when the runner wins (both parts win): {@code s(p − 1) + s(p − 1)/d}. */
    public static BigDecimal eachWayProfitIfWins(BigDecimal stake, BigDecimal price, BigDecimal divisor) {
        return MoneyPolicy.amount(stake.multiply(price.subtract(BigDecimal.ONE))
                .add(stake.multiply(eachWayPlacePrice(price, divisor).subtract(BigDecimal.ONE))));
    }

    /** Gross profit when the runner is placed but does not win: {@code s(p − 1)/d − s}. */
    public static BigDecimal eachWayProfitIfPlaced(BigDecimal stake, BigDecimal price, BigDecimal divisor) {
        return MoneyPolicy.amount(stake.multiply(eachWayPlacePrice(price, divisor).subtract(BigDecimal.ONE)).subtract(stake));
    }

    /** LAY stake whose liability is at most {@code liability}: {@code L / (p − 1)}, rounded down. */
    public static BigDecimal layStakeForLiability(BigDecimal liability, BigDecimal price) {
        return liability.divide(price.subtract(BigDecimal.ONE), MoneyPolicy.CURRENCY_SCALE, RoundingMode.DOWN);
    }

    /** Stake giving gross profit {@code target} if the bet wins: BACK {@code T / (p − 1)}, LAY {@code T}. */
    public static BigDecimal stakeForTargetProfit(BetSide side, BigDecimal target, BigDecimal price) {
        if (side == BetSide.LAY) {
            return MoneyPolicy.stake(target);
        }
        return target.divide(price.subtract(BigDecimal.ONE), MoneyPolicy.CURRENCY_SCALE, RoundingMode.DOWN);
    }
}
