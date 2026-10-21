package com.stellartrack.decision.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionSnapshotRepository extends JpaRepository<DecisionSnapshotEntity, UUID> {

    Optional<DecisionSnapshotEntity> findByRecommendationId(UUID recommendationId);
}
