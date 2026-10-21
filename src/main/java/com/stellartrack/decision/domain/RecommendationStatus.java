package com.stellartrack.decision.domain;

/**
 * Decision-stage status of a recommendation. This is a decision on a proposal — never a Betfair order outcome.
 * <ul>
 *   <li>SKIPPED: evaluated, NO BET (reason recorded).</li>
 *   <li>PENDING_APPROVAL: awaiting administrator review.</li>
 *   <li>APPROVED / REJECTED: administrator decision (rejected proposals are never sent to Betfair).</li>
 *   <li>EXPIRED: not reviewed within the configured timeout.</li>
 *   <li>ACCEPTED: proceeds to order validation without manual review (or an administrator's own manual ticket).</li>
 * </ul>
 */
public enum RecommendationStatus {
    SKIPPED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    EXPIRED,
    ACCEPTED
}
