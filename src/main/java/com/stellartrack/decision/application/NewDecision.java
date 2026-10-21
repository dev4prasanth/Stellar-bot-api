package com.stellartrack.decision.application;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.common.domain.OperatingMode;
import com.stellartrack.configuration.domain.BotConfigurationSettings.SelectionSourceType;
import com.stellartrack.decision.domain.DecisionSnapshot;
import com.stellartrack.decision.domain.RecommendationStatus;
import java.time.Instant;
import java.util.UUID;

/** Everything needed to persist one evaluated recommendation and its immutable snapshot. */
public record NewDecision(
        UUID configurationId,
        Integer configurationVersion,
        OperatingMode mode,
        SelectionSourceType source,
        String marketId,
        long selectionId,
        BetSide side,
        MarketType marketType,
        RecommendationStatus status,
        boolean requiresApproval,
        String noBetReason,
        Instant expiresAt,
        DecisionSnapshot snapshot) {
}
