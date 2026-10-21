package com.stellartrack.messaging.persistence;

import com.stellartrack.messaging.domain.Layer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LayerSourceRecordRepository extends JpaRepository<LayerSourceRecordEntity, UUID> {

    Optional<LayerSourceRecordEntity> findByLayerAndDedupKey(Layer layer, String dedupKey);
}
