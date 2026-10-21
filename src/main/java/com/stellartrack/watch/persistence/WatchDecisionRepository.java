package com.stellartrack.watch.persistence;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchDecisionRepository extends JpaRepository<WatchDecisionEntity, UUID> {

    boolean existsBySessionConfigurationIdAndMarketIdAndSelectionId(UUID sessionConfigurationId, String marketId,
            long selectionId);

    Page<WatchDecisionEntity> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);
}
