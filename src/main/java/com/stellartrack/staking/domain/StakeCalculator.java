package com.stellartrack.staking.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Strategy for one staking method. Implementations compute the unrounded target stake and record the formula
 * steps; rounding, min/max limits and economics are applied uniformly by {@link StakeCalculatorChain}.
 */
public interface StakeCalculator {

    StakingMethodType method();

    /**
     * @return the raw stake before rounding/limits
     * @throws IllegalArgumentException with a human-readable reason when inputs are insufficient
     */
    BigDecimal rawStake(StakeContext context, List<StakeCalculation.Step> steps);
}
