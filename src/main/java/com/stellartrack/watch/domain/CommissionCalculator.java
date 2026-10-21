package com.stellartrack.watch.domain;

import com.stellartrack.common.money.MoneyPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Exchange commission on simulated winnings. Betfair charges commission on a customer's <em>net</em> winnings in a
 * market, at the market's base rate less the account's discount: {@code max(0, net) × base% × (1 − discount%)}.
 * The market's commission is then attributed to the bets with a positive gross profit, in proportion to it, so
 * every bet carries a net figure that adds up to the market total.
 */
public final class CommissionCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private CommissionCalculator() {
    }

    /**
     * @param baseRatePercent     market base rate in percent (e.g. 5)
     * @param discountRatePercent account discount in percent (e.g. 0), clamped to [0, 100]
     */
    public static BigDecimal commission(BigDecimal netMarketProfit, BigDecimal baseRatePercent, BigDecimal discountRatePercent) {
        if (netMarketProfit == null || netMarketProfit.signum() <= 0 || baseRatePercent == null || baseRatePercent.signum() <= 0) {
            return MoneyPolicy.zero();
        }
        BigDecimal discount = discountRatePercent == null ? BigDecimal.ZERO : discountRatePercent.max(BigDecimal.ZERO).min(HUNDRED);
        return MoneyPolicy.amount(netMarketProfit
                .multiply(baseRatePercent).divide(HUNDRED, MoneyPolicy.CALCULATION_SCALE, MoneyPolicy.AMOUNT_ROUNDING)
                .multiply(BigDecimal.ONE.subtract(
                        discount.divide(HUNDRED, MoneyPolicy.CALCULATION_SCALE, MoneyPolicy.AMOUNT_ROUNDING))));
    }

    /**
     * Splits a market's commission over its bets' gross profits (same order as {@code grossProfits}). Bets without a
     * positive gross profit carry none; rounding differences go to the last contributing bet.
     */
    public static List<BigDecimal> attribute(BigDecimal commission, List<BigDecimal> grossProfits) {
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal positiveTotal = grossProfits.stream().filter(g -> g.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        int lastPositive = -1;
        for (int i = 0; i < grossProfits.size(); i++) {
            if (grossProfits.get(i).signum() > 0) {
                lastPositive = i;
            }
        }
        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < grossProfits.size(); i++) {
            BigDecimal gross = grossProfits.get(i);
            BigDecimal share;
            if (commission.signum() <= 0 || gross.signum() <= 0) {
                share = MoneyPolicy.zero();
            } else if (i == lastPositive) {
                share = commission.subtract(assigned);
            } else {
                share = commission.multiply(gross).divide(positiveTotal, MoneyPolicy.CURRENCY_SCALE, MoneyPolicy.AMOUNT_ROUNDING);
                assigned = assigned.add(share);
            }
            shares.add(share);
        }
        return shares;
    }
}
