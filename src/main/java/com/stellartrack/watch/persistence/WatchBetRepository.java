package com.stellartrack.watch.persistence;

import com.stellartrack.common.domain.BetSide;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface WatchBetRepository extends JpaRepository<WatchBetEntity, UUID> {

    /** Locks the bet row, so a revoke and a simulated fill never interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from WatchBetEntity b where b.id = :id")
    Optional<WatchBetEntity> lockById(UUID id);

    /** Bets whose matching is still open (pending or partially matched). */
    List<WatchBetEntity> findByClosedAtIsNull();

    List<WatchBetEntity> findByMarketIdAndSettledAtIsNull(String marketId);

    List<WatchBetEntity> findBySettledAtIsNull();

    boolean existsBySessionIdAndMarketIdAndSelectionIdAndSideAndSettledAtIsNull(UUID sessionId, String marketId,
            long selectionId, BetSide side);
}
