package com.stellartrack.staking.domain;

import com.stellartrack.common.domain.BetSide;
import java.math.BigDecimal;

/**
 * Inputs to a stake calculation. {@code bankAmount} is resolved by the application layer according to
 * {@link BankBasis}; {@code planStep} is resolved from the configuration's settled sequence for STAKING_PLAN.
 */
public record StakeContext(
        BetSide side,
        BigDecimal price,
        StakingSettings settings,
        BigDecimal bankAmount,
        StakingPlanStep planStep,
        BigDecimal exchangeMinimumStake) {
}
