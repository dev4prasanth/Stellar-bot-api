package com.stellartrack.watch.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchBetFillRepository extends JpaRepository<WatchBetFillEntity, UUID> {

    List<WatchBetFillEntity> findByBetIdOrderBySeqAsc(UUID betId);
}
