package com.stellartrack.staking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.stellartrack.common.domain.BetSide;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StakeCalculatorChainTest {

    private static final BigDecimal EXCHANGE_MIN = new BigDecimal("1.00");
    private final StakeCalculatorChain chain = StakeCalculatorChain.standard();

    private static StakingSettings settings(StakingMethodType method, String amount, String percent, BankBasis basis) {
        return new StakingSettings(method, amount == null ? null : new BigDecimal(amount),
                percent == null ? null : new BigDecimal(percent), basis, null, null, null, null);
    }

    private StakeCalculation calc(BetSide side, String price, StakingSettings s, String bank) {
        return chain.calculate(new StakeContext(side, new BigDecimal(price), s, bank == null ? null : new BigDecimal(bank),
                null, EXCHANGE_MIN));
    }

    @Test
    void backFixedStakeUsesDocumentedFormulas() {
        StakeCalculation c = calc(BetSide.BACK, "4.5", settings(StakingMethodType.FIXED_STAKE, "10", null, null), null);
        assertThat(c.valid()).isTrue();
        assertThat(c.stake()).isEqualByComparingTo("10.00");
        assertThat(c.exposure()).isEqualByComparingTo("10.00");
        assertThat(c.liability()).isNull();
        assertThat(c.estimatedGrossProfit()).isEqualByComparingTo("35.00"); // s × (p − 1)
    }

    @Test
    void layFixedStakeLiabilityAndProfit() {
        StakeCalculation c = calc(BetSide.LAY, "4.5", settings(StakingMethodType.FIXED_STAKE, "10", null, null), null);
        assertThat(c.liability()).isEqualByComparingTo("35.00"); // s × (p − 1)
        assertThat(c.exposure()).isEqualByComparingTo("35.00");
        assertThat(c.estimatedGrossProfit()).isEqualByComparingTo("10.00"); // s
    }

    @Test
    void fixedLiabilityRoundsStakeDownSoLiabilityNeverExceedsTarget() {
        StakeCalculation c = calc(BetSide.LAY, "3.35", settings(StakingMethodType.FIXED_LIABILITY, "50", null, null), null);
        assertThat(c.stake()).isEqualByComparingTo("21.27"); // 50 / 2.35 = 21.2765… rounded down
        assertThat(c.liability()).isLessThanOrEqualTo(new BigDecimal("50.00"));
    }

    @Test
    void liabilityMethodsRejectBackBets() {
        StakeCalculation c = calc(BetSide.BACK, "3.0", settings(StakingMethodType.FIXED_LIABILITY, "50", null, null), null);
        assertThat(c.valid()).isFalse();
        assertThat(c.rejectionReason()).contains("only to LAY");
    }

    @Test
    void percentOfBankUsesBankAndRoundsDown() {
        StakeCalculation c = calc(BetSide.BACK, "2.5",
                settings(StakingMethodType.PERCENT_OF_BANK, null, "2.5", BankBasis.FIXED_BANK), "333.33");
        assertThat(c.stake()).isEqualByComparingTo("8.33"); // 8.33325 → 8.33
        assertThat(c.bankAmount()).isEqualByComparingTo("333.33");
    }

    @Test
    void percentOfBankWithoutBankIsRejected() {
        StakeCalculation c = calc(BetSide.BACK, "2.5",
                settings(StakingMethodType.PERCENT_OF_BANK, null, "2", BankBasis.AVAILABLE_BALANCE), null);
        assertThat(c.valid()).isFalse();
        assertThat(c.rejectionReason()).contains("unavailable");
    }

    @Test
    void targetProfitDiffersBySide() {
        StakeCalculation back = calc(BetSide.BACK, "5.0", settings(StakingMethodType.TARGET_PROFIT, "20", null, null), null);
        StakeCalculation lay = calc(BetSide.LAY, "5.0", settings(StakingMethodType.TARGET_PROFIT, "20", null, null), null);
        assertThat(back.stake()).isEqualByComparingTo("5.00"); // 20 / 4
        assertThat(lay.stake()).isEqualByComparingTo("20.00"); // lay gross profit equals stake
    }

    @Test
    void stakeBelowMinimumIsRejectedNotRaised() {
        StakeCalculation c = calc(BetSide.BACK, "2.0", settings(StakingMethodType.FIXED_STAKE, "0.50", null, null), null);
        assertThat(c.valid()).isFalse();
        assertThat(c.rejectionReason()).contains("below the minimum");
    }

    @Test
    void maximumStakeCapsAndIsReported() {
        StakingSettings s = new StakingSettings(StakingMethodType.FIXED_STAKE, new BigDecimal("100"), null, null, null,
                null, new BigDecimal("25"), null);
        StakeCalculation c = calc(BetSide.BACK, "2.0", s, null);
        assertThat(c.stake()).isEqualByComparingTo("25.00");
        assertThat(c.appliedLimits()).anyMatch(l -> l.contains("Capped"));
    }

    @Test
    void invalidPriceIsRejected() {
        StakeCalculation c = calc(BetSide.BACK, "2.01", settings(StakingMethodType.FIXED_STAKE, "10", null, null), null);
        assertThat(c.valid()).isFalse();
    }

    @Test
    void stakingPlanLiabilityStepConvertsForLay() {
        StakingSettings s = new StakingSettings(StakingMethodType.STAKING_PLAN, null, null, null, null, null, null, UUID.randomUUID());
        StakingPlanStep step = new StakingPlanStep(2, new BigDecimal("30"), StakingPlanStep.AmountType.LIABILITY, null, 3);
        StakeCalculation c = chain.calculate(new StakeContext(BetSide.LAY, new BigDecimal("4.0"), s, null, step, EXCHANGE_MIN));
        assertThat(c.stake()).isEqualByComparingTo("10.00");
        assertThat(c.liability()).isEqualByComparingTo("30.00");
    }
}
