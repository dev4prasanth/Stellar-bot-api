package com.stellartrack.staking.domain;

import com.stellartrack.common.money.MoneyPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Back Dutching: one total stake split across several runners of a WIN market so the profit is the same whichever of
 * them wins. Runner {@code i} at odds {@code p_i} takes {@code s_i = S × (1/p_i) / Σ(1/p_j)}, rounded down to pence,
 * so the return {@code s_i × p_i} is equal for every runner and the profit if one wins is that return minus the total
 * staked. A profit is only possible while the implied probabilities {@code Σ(1/p_j)} stay below 100%.
 */
public final class DutchMath {

    private DutchMath() {
    }

    /** Stakes for {@code total} across {@code prices}, in the same order. */
    public static List<BigDecimal> backStakes(BigDecimal total, List<BigDecimal> prices) {
        BigDecimal book = impliedProbability(prices);
        return prices.stream()
                // Settle the quotient first so representation error (1/12 is not exact) never costs a penny.
                .map(p -> total.multiply(inverse(p)).divide(book, MoneyPolicy.CALCULATION_SCALE, RoundingMode.HALF_EVEN)
                        .setScale(6, RoundingMode.HALF_EVEN).setScale(MoneyPolicy.CURRENCY_SCALE, RoundingMode.DOWN))
                .toList();
    }

    /** Total stake that returns {@code targetProfit} whichever runner wins: {@code P / (1/Σ(1/p) − 1)}. */
    public static BigDecimal totalForTargetProfit(BigDecimal targetProfit, List<BigDecimal> prices) {
        BigDecimal book = impliedProbability(prices);
        BigDecimal margin = BigDecimal.ONE.divide(book, MoneyPolicy.CALCULATION_SCALE, RoundingMode.HALF_EVEN)
                .subtract(BigDecimal.ONE);
        if (margin.signum() <= 0) {
            throw new IllegalArgumentException("These selections cannot return a profit together: their combined chance"
                    + " at these odds is 100% or more");
        }
        return targetProfit.divide(margin, MoneyPolicy.CURRENCY_SCALE, RoundingMode.UP);
    }

    /** Gross profit if the runner at {@code price} with {@code stake} wins, given everything staked on the group. */
    public static BigDecimal profitIfWins(BigDecimal stake, BigDecimal price, BigDecimal totalStaked) {
        return MoneyPolicy.amount(stake.multiply(price).subtract(totalStaked));
    }

    /** {@code Σ(1/p)}: the combined implied probability of the selections (1 = 100%). */
    public static BigDecimal impliedProbability(List<BigDecimal> prices) {
        return prices.stream().map(DutchMath::inverse).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal inverse(BigDecimal price) {
        return BigDecimal.ONE.divide(price, MoneyPolicy.CALCULATION_SCALE, RoundingMode.HALF_EVEN);
    }
}
