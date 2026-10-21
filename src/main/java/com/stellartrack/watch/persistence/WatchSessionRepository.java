package com.stellartrack.watch.persistence;

import com.stellartrack.watch.domain.WatchSessionStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchSessionRepository extends JpaRepository<WatchSessionEntity, UUID> {

    Optional<WatchSessionEntity> findFirstByStatus(WatchSessionStatus status);

    Optional<WatchSessionEntity> findFirstByOrderByStartedAtDesc();

    Page<WatchSessionEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
}
