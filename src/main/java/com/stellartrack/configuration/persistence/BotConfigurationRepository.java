package com.stellartrack.configuration.persistence;

import com.stellartrack.configuration.domain.OperationalState;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotConfigurationRepository extends JpaRepository<BotConfigurationEntity, UUID> {

    List<BotConfigurationEntity> findByArchivedAtIsNullOrderByNameAsc();

    List<BotConfigurationEntity> findAllByOrderByNameAsc();

    List<BotConfigurationEntity> findByOperationalStateIn(Collection<OperationalState> states);

    boolean existsByNameIgnoreCaseAndArchivedAtIsNull(String name);

    long countByOperationalState(OperationalState state);
}
