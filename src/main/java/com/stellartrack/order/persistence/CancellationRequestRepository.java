package com.stellartrack.order.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CancellationRequestRepository extends JpaRepository<CancellationRequestEntity, UUID> {

    List<CancellationRequestEntity> findByBetIdOrderByRequestedAtDesc(String betId);
}
