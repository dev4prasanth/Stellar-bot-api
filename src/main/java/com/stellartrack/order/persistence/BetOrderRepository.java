package com.stellartrack.order.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BetOrderRepository extends JpaRepository<BetOrderEntity, String> {

    Optional<BetOrderEntity> findByIntentId(UUID intentId);

    List<BetOrderEntity> findByStatus(String status);

    @Query("select distinct b.marketId from BetOrderEntity b where b.status = 'EXECUTABLE'")
    List<String> findMarketsWithExecutableOrders();

    @Query("select distinct b.marketId from BetOrderEntity b where b.createdAt >= :since")
    List<String> findMarketIdsWithBetsPlacedSince(@Param("since") Instant since);

    /** Bets without a current settlement revision (still open, or settled but not yet synchronized). */
    @Query(value = """
            select b.bet_id from bet_order b
            where not exists (select 1 from settlement s where s.bet_id = b.bet_id and s.is_current
                              and s.bet_status in ('SETTLED', 'VOIDED'))
            """, nativeQuery = true)
    List<String> findUnsettledBetIds();
}
