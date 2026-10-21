package com.stellartrack.order.application;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.OperatingMode;
import com.stellartrack.configuration.application.ConfigurationService.ResolvedConfiguration;
import com.stellartrack.order.domain.OrderPurpose;
import com.stellartrack.order.port.PersistenceType;
import com.stellartrack.staking.domain.StakeCalculation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A request to submit one order. Every field is revalidated by the backend regardless of origin
 * (manual ticket, approved proposal or automatic decision).
 *
 * @param mode                 origin of the order (configuration pipeline or administrator ticket); drives the
 *                             origin-specific pre-trade rules (Stop All, entry window, session limits and stops)
 * @param tradingMode          the mode the bet is placed under: MANUAL when a human confirmed it (approved proposal
 *                             or bet ticket), AUTOMATIC when it is submitted without human approval
 * @param reviewedPrice        manual only: the price the administrator confirmed
 * @param acceptDelayedData    manual only: the administrator acknowledged that market data is delayed
 * @param session              automatic only: the automation run this entry belongs to
 * @param dutchGroupId         manual only: the Dutch bet this order is one leg of; null for a single bet
 */
public record PlacementCommand(
        OperatingMode mode,
        OperatingMode tradingMode,
        OrderPurpose purpose,
        UUID linkedIntentId,
        UUID recommendationId,
        ResolvedConfiguration configuration,
        String marketId,
        long selectionId,
        BetSide side,
        BigDecimal price,
        StakeCalculation stake,
        PersistenceType persistenceType,
        String idempotencyKey,
        String actor,
        BigDecimal reviewedPrice,
        boolean acceptDelayedData,
        AutomationSession session,
        UUID dutchGroupId) {

    public PlacementCommand(OperatingMode mode, OperatingMode tradingMode, OrderPurpose purpose, UUID linkedIntentId,
            UUID recommendationId, ResolvedConfiguration configuration, String marketId, long selectionId, BetSide side,
            BigDecimal price, StakeCalculation stake, PersistenceType persistenceType, String idempotencyKey, String actor,
            BigDecimal reviewedPrice, boolean acceptDelayedData, AutomationSession session) {
        this(mode, tradingMode, purpose, linkedIntentId, recommendationId, configuration, marketId, selectionId, side, price,
                stake, persistenceType, idempotencyKey, actor, reviewedPrice, acceptDelayedData, session, null);
    }

    /** The automation run context used for session-scoped limits and stops. */
    public record AutomationSession(UUID runId, Instant startedAt, BigDecimal bankAtStart) {
    }
}
