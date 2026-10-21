package com.stellartrack.decision.persistence;

import com.stellartrack.decision.domain.RecommendationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RecommendationRepository extends JpaRepository<RecommendationEntity, UUID>,
        JpaSpecificationExecutor<RecommendationEntity> {

    List<RecommendationEntity> findByStatusOrderByCreatedAtAsc(RecommendationStatus status);

    List<RecommendationEntity> findByStatusAndConfigurationIdOrderByCreatedAtAsc(RecommendationStatus status,
            UUID configurationId);

    List<RecommendationEntity> findByStatusAndExpiresAtBefore(RecommendationStatus status, Instant now);

    List<RecommendationEntity> findByMarketId(String marketId);

    boolean existsByMarketIdAndSelectionIdAndConfigurationIdAndStatusIn(String marketId, long selectionId,
            UUID configurationId, List<RecommendationStatus> statuses);
}
