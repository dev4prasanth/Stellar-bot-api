package com.stellartrack.watch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchMarketCommissionRepository
        extends JpaRepository<WatchMarketCommissionEntity, WatchMarketCommissionEntity.Key> {
}
