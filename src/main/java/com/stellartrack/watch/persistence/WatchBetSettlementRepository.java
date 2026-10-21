package com.stellartrack.watch.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchBetSettlementRepository extends JpaRepository<WatchBetSettlementEntity, UUID> {

    Optional<WatchBetSettlementEntity> findByBetIdAndCurrentTrue(UUID betId);
}
