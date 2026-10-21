package com.stellartrack.order.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationCheckpointRepository extends JpaRepository<ReconciliationCheckpointEntity, String> {
}
