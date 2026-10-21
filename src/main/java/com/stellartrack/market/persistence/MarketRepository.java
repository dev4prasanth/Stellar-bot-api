package com.stellartrack.market.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketRepository extends JpaRepository<MarketEntity, String> {

    List<MarketEntity> findByMarketStartTimeBetweenOrderByMarketStartTimeAsc(Instant from, Instant to);

    @Query("""
            select m from MarketEntity m
            where m.resultCapturedAt is null and m.marketStartTime < :startedBefore and m.marketStartTime > :notOlderThan
            order by m.marketStartTime asc""")
    List<MarketEntity> findAwaitingResult(@Param("startedBefore") Instant startedBefore,
            @Param("notOlderThan") Instant notOlderThan, Pageable pageable);

    /** Upcoming markets plus any market where the application holds an unsettled order. */
    @Query(value = """
            select m.market_id from market m
            where m.market_start_time between :from and :to and coalesce(m.status, 'OPEN') <> 'CLOSED'
            union
            select distinct b.market_id from bet_order b where b.status = 'EXECUTABLE'
            limit :limit""", nativeQuery = true)
    List<String> findStreamCandidates(@Param("from") Instant from, @Param("to") Instant to, @Param("limit") int limit);
}
