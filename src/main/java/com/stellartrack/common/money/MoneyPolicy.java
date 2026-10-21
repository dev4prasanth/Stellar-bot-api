package com.stellartrack.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Authoritative precision and rounding rules for monetary values.
 *
 * <ul>
 *   <li>Currency amounts use two decimal places (Betfair account currencies are all 2dp).</li>
 *   <li>Stakes are rounded DOWN so a calculated stake never exceeds a configured limit or bank share.</li>
 *   <li>Derived amounts (profit, liability, aggregates) use HALF_EVEN to avoid systematic bias.</li>
 *   <li>Intermediate calculations keep {@link #CALCULATION_SCALE} decimals before final rounding.</li>
 * </ul>
 */
public final class MoneyPolicy {

    public static final int CURRENCY_SCALE = 2;
    public static final int CALCULATION_SCALE = 10;
    public static final RoundingMode STAKE_ROUNDING = RoundingMode.DOWN;
    public static final RoundingMode AMOUNT_ROUNDING = RoundingMode.HALF_EVEN;

    private MoneyPolicy() {
    }

    public static BigDecimal stake(BigDecimal value) {
        return value.setScale(CURRENCY_SCALE, STAKE_ROUNDING);
    }

    public static BigDecimal amount(BigDecimal value) {
        return value.setScale(CURRENCY_SCALE, AMOUNT_ROUNDING);
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(CURRENCY_SCALE);
    }

    public static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? zero() : value;
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
