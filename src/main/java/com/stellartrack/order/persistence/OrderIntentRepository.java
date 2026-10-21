package com.stellartrack.order.persistence;

import com.stellartrack.order.domain.OrderIntentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OrderIntentRepository extends JpaRepository<OrderIntentEntity, UUID>,
        JpaSpecificationExecutor<OrderIntentEntity> {

    Optional<OrderIntentEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<OrderIntentEntity> findByCustomerOrderRef(String customerOrderRef);

    List<OrderIntentEntity> findByStatus(OrderIntentStatus status);

    List<OrderIntentEntity> findByStatusAndUpdatedAtBefore(OrderIntentStatus status, Instant before);

    List<OrderIntentEntity> findByRecommendationId(UUID recommendationId);

    /** The legs of a Dutch bet. */
    List<OrderIntentEntity> findByDutchGroupId(UUID dutchGroupId);
}
