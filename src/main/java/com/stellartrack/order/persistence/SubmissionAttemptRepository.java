package com.stellartrack.order.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionAttemptRepository extends JpaRepository<SubmissionAttemptEntity, UUID> {

    List<SubmissionAttemptEntity> findByIntentIdOrderByAttemptNoAsc(UUID intentId);

    int countByIntentId(UUID intentId);
}
