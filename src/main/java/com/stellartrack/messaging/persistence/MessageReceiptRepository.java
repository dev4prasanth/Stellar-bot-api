package com.stellartrack.messaging.persistence;

import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.messaging.domain.ReceiptStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageReceiptRepository extends JpaRepository<MessageReceiptEntity, UUID> {

    Page<MessageReceiptEntity> findByLayer(Layer layer, Pageable pageable);

    Page<MessageReceiptEntity> findByLayerAndStatus(Layer layer, ReceiptStatus status, Pageable pageable);

    Page<MessageReceiptEntity> findByStatus(ReceiptStatus status, Pageable pageable);

    boolean existsByLayerAndDedupKeyAndStatus(Layer layer, String dedupKey, ReceiptStatus status);

    @Query("select max(r.processedAt) from MessageReceiptEntity r where r.layer = :layer and r.status = 'ACCEPTED'")
    Optional<Instant> lastAcceptedAt(@Param("layer") Layer layer);

    @Query("select max(r.sourceEventAt) from MessageReceiptEntity r where r.layer = :layer and r.status = 'ACCEPTED'")
    Optional<Instant> lastAcceptedSourceEventAt(@Param("layer") Layer layer);

    @Query("select max(r.receivedAt) from MessageReceiptEntity r where r.layer = :layer")
    Optional<Instant> lastReceivedAt(@Param("layer") Layer layer);

    @Query("""
            select r.status as status, count(r) as total from MessageReceiptEntity r
            where r.layer = :layer and r.receivedAt >= :since group by r.status""")
    List<StatusCount> countByStatusSince(@Param("layer") Layer layer, @Param("since") Instant since);

    interface StatusCount {
        ReceiptStatus getStatus();

        long getTotal();
    }
}
