package com.stellartrack.staking.domain;

/**
 * What "the bank" means for percentage-based methods.
 * <ul>
 *   <li>AVAILABLE_BALANCE: Betfair available-to-bet balance at calculation time (shared account funds).</li>
 *   <li>ALLOCATED_CAPITAL: the configuration's internal capital allocation (a limit, not a separate wallet).</li>
 *   <li>FIXED_BANK: an administrator-entered amount that does not change with results.</li>
 * </ul>
 */
public enum BankBasis {
    AVAILABLE_BALANCE,
    ALLOCATED_CAPITAL,
    FIXED_BANK
}
