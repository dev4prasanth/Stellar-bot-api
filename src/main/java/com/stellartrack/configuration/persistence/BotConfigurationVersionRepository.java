package com.stellartrack.configuration.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotConfigurationVersionRepository extends JpaRepository<BotConfigurationVersionEntity, UUID> {

    Optional<BotConfigurationVersionEntity> findByConfigurationIdAndVersion(UUID configurationId, int version);

    List<BotConfigurationVersionEntity> findByConfigurationIdOrderByVersionDesc(UUID configurationId);
}
