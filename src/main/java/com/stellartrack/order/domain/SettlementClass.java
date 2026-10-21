package com.stellartrack.order.domain;

/**
 * Reporting classification of a current settlement revision. Defined once here and mirrored by the
 * v_settled_bet view so dashboard tiles, charts and exports share the same meaning.
 * <ul>
 *   <li>WON / LOST: settled with positive / negative provider profit.</li>
 *   <li>WON_REDUCED: positive profit with a Betfair price reduction (reduction factor applied).</li>
 *   <li>SETTLED_ZERO: settled with zero profit (cannot faithfully be called a win or loss).</li>
 *   <li>VOID: voided by Betfair. UNMATCHED: lapsed or cancelled without a settled matched portion.</li>
 * </ul>
 */
public enum SettlementClass {
    WON,
    WON_REDUCED,
    LOST,
    SETTLED_ZERO,
    VOID,
    UNMATCHED
}
