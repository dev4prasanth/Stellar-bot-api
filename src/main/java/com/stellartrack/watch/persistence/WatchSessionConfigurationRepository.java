package com.stellartrack.watch.persistence;

import com.stellartrack.watch.domain.WatchConfigurationState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchSessionConfigurationRepository extends JpaRepository<WatchSessionConfigurationEntity, UUID> {

    List<WatchSessionConfigurationEntity> findBySessionIdOrderByConfigurationNameAsc(UUID sessionId);

    List<WatchSessionConfigurationEntity> findBySessionIdAndState(UUID sessionId, WatchConfigurationState state);

    Optional<WatchSessionConfigurationEntity> findBySessionIdAndConfigurationId(UUID sessionId, UUID configurationId);
}
