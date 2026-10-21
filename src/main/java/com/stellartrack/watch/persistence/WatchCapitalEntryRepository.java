package com.stellartrack.watch.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchCapitalEntryRepository extends JpaRepository<WatchCapitalEntryEntity, UUID> {

    Optional<WatchCapitalEntryEntity> findFirstBySessionIdOrderBySeqDesc(UUID sessionId);

    List<WatchCapitalEntryEntity> findBySessionIdOrderBySeqAsc(UUID sessionId);
}
