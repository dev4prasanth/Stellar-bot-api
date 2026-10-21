package com.stellartrack.staking.domain;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Staking configuration.
 *
 * @param amount  FIXED_STAKE stake, FIXED_LIABILITY liability, or TARGET_PROFIT gross profit target
 * @param percent PERCENT_OF_BANK / PERCENT_OF_BANK_LIABILITY percentage (0 &lt; percent ≤ 100)
 * @param minimumStake stakes below this are rejected (never rounded up); defaults to the exchange minimum
 * @param maximumStake stakes above this are capped down, with the cap shown in the calculation
 */
public record StakingSettings(
        @NotNull StakingMethodType method,
        @DecimalMin(value = "0.01") BigDecimal amount,
        @DecimalMin(value = "0.0001") @DecimalMax(value = "100") BigDecimal percent,
        BankBasis bankBasis,
        @DecimalMin(value = "0.01") BigDecimal fixedBank,
        @DecimalMin(value = "0.01") BigDecimal minimumStake,
        @DecimalMin(value = "0.01") BigDecimal maximumStake,
        UUID stakingPlanId) {

    @AssertTrue(message = "required staking fields are missing for the selected method")
    public boolean isComplete() {
        if (method == null) {
            return true;
        }
        return switch (method) {
            case FIXED_STAKE, FIXED_LIABILITY, TARGET_PROFIT -> amount != null;
            case PERCENT_OF_BANK, PERCENT_OF_BANK_LIABILITY -> percent != null && bankBasis != null
                    && (bankBasis != BankBasis.FIXED_BANK || fixedBank != null);
            case STAKING_PLAN -> stakingPlanId != null;
        };
    }

    @AssertTrue(message = "minimumStake must not exceed maximumStake")
    public boolean isMinNotAboveMax() {
        return minimumStake == null || maximumStake == null || minimumStake.compareTo(maximumStake) <= 0;
    }
}
