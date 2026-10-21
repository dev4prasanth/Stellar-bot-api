package com.stellartrack.staking.application;

import com.stellartrack.staking.domain.StakingSettings;
import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * Resolves the bank amount for percentage-based staking. Implemented by the capital module, which owns
 * account funds; staking stays independent of where balances come from.
 */
public interface BankResolver {

    /**
     * @param allocatedCapital the configuration's internal allocation, used for ALLOCATED_CAPITAL (may be null)
     * @return the bank, or null when it cannot be determined from current, authoritative data
     */
    BigDecimal resolve(StakingSettings settings, BigDecimal allocatedCapital);

    /**
     * The bank for the settings' basis, given where the available balance comes from (real funds for live bets, the
     * virtual capital for Watch Mode).
     */
    static BigDecimal byBasis(StakingSettings settings, BigDecimal allocatedCapital, Supplier<BigDecimal> availableBalance) {
        if (settings.bankBasis() == null) {
            return null;
        }
        return switch (settings.bankBasis()) {
            case AVAILABLE_BALANCE -> availableBalance.get();
            case ALLOCATED_CAPITAL -> allocatedCapital;
            case FIXED_BANK -> settings.fixedBank();
        };
    }
}
