package com.stellartrack.staking.domain;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.money.MoneyPolicy;
import com.stellartrack.common.money.PriceLadder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a method's raw stake and then the uniform rules: round DOWN to 2dp, cap at the maximum, reject below
 * the minimum (exchange minimum or configured minimum, whichever is higher). Pure and deterministic, so the
 * backend result is authoritative and reproducible.
 */
public final class StakeCalculatorChain {

    private final Map<StakingMethodType, StakeCalculator> calculators = new EnumMap<>(StakingMethodType.class);

    public StakeCalculatorChain(List<StakeCalculator> calculators) {
        calculators.forEach(c -> this.calculators.put(c.method(), c));
    }

    public static StakeCalculatorChain standard() {
        return new StakeCalculatorChain(StakeCalculators.all());
    }

    public StakeCalculation calculate(StakeContext context) {
        StakingSettings settings = context.settings();
        List<StakeCalculation.Step> steps = new ArrayList<>();
        List<String> limits = new ArrayList<>();
        StakingMethodType method = settings.method();
        if (context.price() == null || !PriceLadder.isValid(context.price())) {
            return StakeCalculation.rejected(method, context.side(), context.price(), steps, limits,
                    "Price is missing or not a valid Betfair price");
        }
        if (method.layOnly() && context.side() != BetSide.LAY) {
            return StakeCalculation.rejected(method, context.side(), context.price(), steps, limits,
                    method + " applies only to LAY bets");
        }
        StakeCalculator calculator = calculators.get(method);
        BigDecimal raw;
        try {
            raw = calculator.rawStake(context, steps);
        } catch (IllegalArgumentException e) {
            return StakeCalculation.rejected(method, context.side(), context.price(), steps, limits, e.getMessage());
        }
        BigDecimal stake = MoneyPolicy.stake(raw);
        if (stake.compareTo(raw) != 0) {
            limits.add("Rounded down to " + stake.toPlainString() + " (2 decimal places)");
        }
        if (settings.maximumStake() != null && stake.compareTo(settings.maximumStake()) > 0) {
            stake = MoneyPolicy.stake(settings.maximumStake());
            limits.add("Capped at maximum stake " + stake.toPlainString());
        }
        BigDecimal minimum = effectiveMinimum(settings.minimumStake(), context.exchangeMinimumStake());
        if (minimum != null && stake.compareTo(minimum) < 0) {
            return StakeCalculation.rejected(method, context.side(), context.price(), steps, limits,
                    "Calculated stake " + stake.toPlainString() + " is below the minimum stake " + minimum.toPlainString());
        }
        if (stake.signum() <= 0) {
            return StakeCalculation.rejected(method, context.side(), context.price(), steps, limits, "Calculated stake is zero");
        }
        BigDecimal liability = context.side() == BetSide.LAY ? BetMath.layLiability(stake, context.price()) : null;
        BigDecimal exposure = BetMath.exposure(context.side(), stake, context.price());
        BigDecimal profit = BetMath.grossProfitIfWins(context.side(), stake, context.price());
        steps.add(new StakeCalculation.Step("Final stake", "after rounding and limits", stake));
        if (liability != null) {
            steps.add(new StakeCalculation.Step("Liability", "stake × (p − 1)", liability));
        }
        steps.add(new StakeCalculation.Step("Estimated gross profit if the bet wins",
                context.side() == BetSide.BACK ? "stake × (p − 1)" : "stake", profit));
        return new StakeCalculation(method, context.side(), context.price(), stake, liability, exposure, profit,
                settings.bankBasis(), context.bankAmount(), List.copyOf(steps), List.copyOf(limits), true, null);
    }

    private static BigDecimal effectiveMinimum(BigDecimal configured, BigDecimal exchange) {
        if (configured == null) {
            return exchange;
        }
        if (exchange == null) {
            return configured;
        }
        return configured.max(exchange);
    }
}
