package com.stellartrack.staking.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StakingPlanRepository extends JpaRepository<StakingPlanEntity, UUID> {

    boolean existsByName(String name);
}
