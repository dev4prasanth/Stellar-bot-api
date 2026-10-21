package com.stellartrack.staking.domain;

/** Agreed staking methods. LAY-only methods are rejected for BACK bets. */
public enum StakingMethodType {
    FIXED_STAKE(false),
    PERCENT_OF_BANK(false),
    FIXED_LIABILITY(true),
    PERCENT_OF_BANK_LIABILITY(true),
    TARGET_PROFIT(false),
    STAKING_PLAN(false);

    private final boolean layOnly;

    StakingMethodType(boolean layOnly) {
        this.layOnly = layOnly;
    }

    public boolean layOnly() {
        return layOnly;
    }
}
