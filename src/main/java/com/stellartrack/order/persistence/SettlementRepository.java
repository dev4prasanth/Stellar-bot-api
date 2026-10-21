package com.stellartrack.order.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<SettlementEntity, UUID> {

    Optional<SettlementEntity> findByBetIdAndBetStatusAndCurrentTrue(String betId, String betStatus);

    List<SettlementEntity> findByBetIdOrderByBetStatusAscRevisionAsc(String betId);
}
