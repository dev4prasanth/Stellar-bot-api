package com.stellartrack.order.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketSettlementRepository extends JpaRepository<MarketSettlementEntity, UUID> {

    Optional<MarketSettlementEntity> findByMarketIdAndCurrentTrue(String marketId);
}
