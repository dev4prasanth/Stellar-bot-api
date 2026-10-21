package com.stellartrack.staking.domain;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.money.MoneyPolicy;
import java.math.BigDecimal;
import java.util.List;

/** The agreed staking method implementations. */
public final class StakeCalculators {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private StakeCalculators() {
    }

    public static List<StakeCalculator> all() {
        return List.of(new FixedStake(), new PercentOfBank(), new FixedLiability(), new PercentOfBankLiability(),
                new TargetProfit(), new Plan());
    }

    /** Stake = configured amount. */
    static final class FixedStake implements StakeCalculator {
        @Override
        public StakingMethodType method() {
            return StakingMethodType.FIXED_STAKE;
        }

        @Override
        public BigDecimal rawStake(StakeContext c, List<StakeCalculation.Step> steps) {
            steps.add(new StakeCalculation.Step("Fixed stake", "stake = amount", c.settings().amount()));
            return c.settings().amount();
        }
    }

    /** Stake = bank × percent / 100. */
    static final class PercentOfBank implements StakeCalculator {
        @Override
        public StakingMethodType method() {
            return StakingMethodType.PERCENT_OF_BANK;
        }

        @Override
        public BigDecimal rawStake(StakeContext c, List<StakeCalculation.Step> steps) {
            BigDecimal bank = requireBank(c);
            BigDecimal stake = bank.multiply(c.settings().percent()).divide(HUNDRED, MoneyPolicy.CALCULATION_SCALE,
                    MoneyPolicy.AMOUNT_ROUNDING);
            steps.add(new StakeCalculation.Step("Bank (" + c.settings().bankBasis() + ")", "bank", bank));
            steps.add(new StakeCalculation.Step("Stake", "bank × " + c.settings().percent().toPlainString() + "%", stake));
            return stake;
        }
    }

    /** LAY stake = liability / (p − 1). */
    static final class FixedLiability implements StakeCalculator {
        @Override
        public StakingMethodType method() {
            return StakingMethodType.FIXED_LIABILITY;
        }

        @Override
        public BigDecimal rawStake(StakeContext c, List<StakeCalculation.Step> steps) {
            BigDecimal liability = c.settings().amount();
            BigDecimal stake = BetMath.layStakeForLiability(liability, c.price());
            steps.add(new StakeCalculation.Step("Target liability", "liability = amount", liability));
            steps.add(new StakeCalculation.Step("Lay stake", "liability ÷ (p − 1)", stake));
            return stake;
        }
    }

    /** LAY stake = (bank × percent / 100) / (p − 1). */
    static final class PercentOfBankLiability implements StakeCalculator {
        @Override
        public StakingMethodType method() {
            return StakingMethodType.PERCENT_OF_BANK_LIABILITY;
        }

        @Override
        public BigDecimal rawStake(StakeContext c, List<StakeCalculation.Step> steps) {
            BigDecimal bank = requireBank(c);
            BigDecimal liability = bank.multiply(c.settings().percent()).divide(HUNDRED, MoneyPolicy.CURRENCY_SCALE,
                    java.math.RoundingMode.DOWN);
            BigDecimal stake = BetMath.layStakeForLiability(liability, c.price());
            steps.add(new StakeCalculation.Step("Bank (" + c.settings().bankBasis() + ")", "bank", bank));
            steps.add(new StakeCalculation.Step("Target liability", "bank × " + c.settings().percent().toPlainString() + "%", liability));
            steps.add(new StakeCalculation.Step("Lay stake", "liability ÷ (p − 1)", stake));
            return stake;
        }
    }

    /** BACK stake = T / (p − 1); LAY stake = T (LAY gross profit equals the backer's stake). */
    static final class TargetProfit implements StakeCalculator {
        @Override
        public StakingMethodType method() {
            return StakingMethodType.TARGET_PROFIT;
        }

        @Override
        public BigDecimal rawStake(StakeContext c, List<StakeCalculation.Step> steps) {
            BigDecimal target = c.settings().amount();
            BigDecimal stake = BetMath.stakeForTargetProfit(c.side(), target, c.price());
            steps.add(new StakeCalculation.Step("Target gross profit", "target = amount", target));
            steps.add(new StakeCalculation.Step("Stake", c.side() == BetSide.BACK ? "target ÷ (p − 1)" : "target", stake));
            return stake;
        }
    }

    /** Stake from the current plan step; LIABILITY steps are converted for LAY with L / (p − 1). */
    static final class Plan implements StakeCalculator {
        @Override
        public StakingMethodType method() {
            return StakingMethodType.STAKING_PLAN;
        }

        @Override
        public BigDecimal rawStake(StakeContext c, List<StakeCalculation.Step> steps) {
            StakingPlanStep step = c.planStep();
            if (step == null) {
                throw new IllegalArgumentException("Staking plan step could not be resolved");
            }
            steps.add(new StakeCalculation.Step("Plan step " + step.step() + " (" + step.amountType() + ")", "amount", step.amount()));
            if (step.amountType() == StakingPlanStep.AmountType.LIABILITY) {
                if (c.side() != BetSide.LAY) {
                    throw new IllegalArgumentException("Liability plan steps apply only to LAY bets");
                }
                BigDecimal stake = BetMath.layStakeForLiability(step.amount(), c.price());
                steps.add(new StakeCalculation.Step("Lay stake", "liability ÷ (p − 1)", stake));
                return stake;
            }
            return step.amount();
        }
    }

    private static BigDecimal requireBank(StakeContext c) {
        if (c.bankAmount() == null || c.bankAmount().signum() <= 0) {
            throw new IllegalArgumentException("Bank amount for " + c.settings().bankBasis() + " is unavailable or not positive");
        }
        return c.bankAmount();
    }
}
