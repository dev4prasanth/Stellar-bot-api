package com.stellartrack.decision.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionOutcomeEvaluationRepository extends JpaRepository<DecisionOutcomeEvaluationEntity, UUID> {

    boolean existsByRecommendationIdAndRuleVersion(UUID recommendationId, String ruleVersion);

    List<DecisionOutcomeEvaluationEntity> findByRecommendationIdOrderByEvaluatedAtDesc(UUID recommendationId);
}
