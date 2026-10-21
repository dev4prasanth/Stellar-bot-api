package com.stellartrack.staking.domain;

import com.stellartrack.common.domain.BetSide;
import java.math.BigDecimal;
import java.util.List;

/**
 * Auditable stake calculation. Persisted with each order intention and decision snapshot so the exact inputs,
 * formulas and limits behind every stake can be reproduced.
 *
 * @param exposure         stake for BACK, liability for LAY
 * @param estimatedGrossProfit estimate before commission, assuming a full match at {@code price}; not earned money
 */
public record StakeCalculation(
        StakingMethodType method,
        BetSide side,
        BigDecimal price,
        BigDecimal stake,
        BigDecimal liability,
        BigDecimal exposure,
        BigDecimal estimatedGrossProfit,
        BankBasis bankBasis,
        BigDecimal bankAmount,
        List<Step> steps,
        List<String> appliedLimits,
        boolean valid,
        String rejectionReason) {

    public record Step(String label, String formula, BigDecimal value) {
    }

    /**
     * The same calculation for an EACH_WAY market: the stake is per part, so the money at risk is the total stake
     * {@code 2s} and the estimated profit is the win-and-place figure. Refused for LAY (Each-Way is Back only) and
     * when the market's place terms are unknown.
     *
     * @param divisor place terms: the place part pays at 1/divisor of the win odds
     */
    public StakeCalculation forEachWay(BigDecimal divisor) {
        if (!valid) {
            return this;
        }
        if (side != BetSide.BACK) {
            return rejected(method, side, price, steps, appliedLimits, "Each-Way bets can only be Back bets");
        }
        if (divisor == null || divisor.signum() <= 0) {
            return rejected(method, side, price, steps, appliedLimits,
                    "The place terms of this Each-Way market are not known yet");
        }
        BigDecimal total = BetMath.eachWayTotalStake(stake);
        BigDecimal ifWins = BetMath.eachWayProfitIfWins(stake, price, divisor);
        List<Step> all = new java.util.ArrayList<>(steps);
        all.add(new Step("Each-Way total stake", "stake × 2 (win part + place part)", total));
        all.add(new Step("Place part odds", "1 + (p − 1) / " + divisor.stripTrailingZeros().toPlainString(),
                BetMath.eachWayPlacePrice(price, divisor).setScale(2, java.math.RoundingMode.HALF_EVEN)));
        all.add(new Step("Estimated gross profit if it wins", "win part + place part", ifWins));
        all.add(new Step("Estimated gross profit if placed only", "place part − win stake",
                BetMath.eachWayProfitIfPlaced(stake, price, divisor)));
        return new StakeCalculation(method, side, price, stake, null, total, ifWins, bankBasis, bankAmount,
                List.copyOf(all), appliedLimits, true, null);
    }

    public static StakeCalculation rejected(StakingMethodType method, BetSide side, BigDecimal price, List<Step> steps,
            List<String> limits, String reason) {
        return new StakeCalculation(method, side, price, null, null, null, null, null, null, List.copyOf(steps),
                List.copyOf(limits), false, reason);
    }
}
