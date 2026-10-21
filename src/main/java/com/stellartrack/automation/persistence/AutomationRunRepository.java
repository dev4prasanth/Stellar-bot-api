package com.stellartrack.automation.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutomationRunRepository extends JpaRepository<AutomationRunEntity, UUID> {

    Optional<AutomationRunEntity> findByConfigurationIdAndStoppedAtIsNull(UUID configurationId);

    List<AutomationRunEntity> findByStoppedAtIsNull();

    List<AutomationRunEntity> findByConfigurationIdOrderByStartedAtDesc(UUID configurationId, Pageable pageable);

    List<AutomationRunEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
}
