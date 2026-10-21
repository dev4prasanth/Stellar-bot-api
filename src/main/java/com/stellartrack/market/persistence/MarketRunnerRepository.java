package com.stellartrack.market.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketRunnerRepository extends JpaRepository<MarketRunnerEntity, MarketRunnerEntity.Key> {

    @Query("select r from MarketRunnerEntity r where r.id.marketId = :marketId order by r.sortPriority")
    List<MarketRunnerEntity> findByMarketId(@Param("marketId") String marketId);
}
