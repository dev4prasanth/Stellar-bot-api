package com.stellartrack.messaging.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LayerRecordMarketLinkRepository
        extends JpaRepository<LayerRecordMarketLinkEntity, LayerRecordMarketLinkEntity.Key> {
}
