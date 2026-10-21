package com.stellartrack.capital.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountFundsSnapshotRepository extends JpaRepository<AccountFundsSnapshotEntity, UUID> {

    Optional<AccountFundsSnapshotEntity> findFirstByOrderByCapturedAtDesc();

    List<AccountFundsSnapshotEntity> findByCapturedAtBetweenOrderByCapturedAtAsc(Instant from, Instant to);
}
