package com.stellartrack.staking.domain;

import java.math.BigDecimal;

/**
 * One row of an imported staking plan (schema version 1).
 *
 * @param amountType STAKE (the bet stake) or LIABILITY (LAY liability; converted with {@code L / (p − 1)})
 * @param onWin      next step after a winning settled entry, or {@code null} to reset to step 1
 * @param onLoss     next step after a losing settled entry, or {@code null} to reset to step 1
 */
public record StakingPlanStep(int step, BigDecimal amount, AmountType amountType, Integer onWin, Integer onLoss) {

    public enum AmountType {
        STAKE,
        LIABILITY
    }
}
