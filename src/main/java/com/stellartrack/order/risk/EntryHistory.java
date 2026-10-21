package com.stellartrack.order.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only history of entries that the pre-trade risk rules and staking plans are evaluated against. Live trading
 * reads real orders and settlements; Watch Mode reads its simulated bets. {@code scopeId} identifies whose entries
 * are counted: the configuration for live orders, the session configuration for Watch bets.
 */
public interface EntryHistory {

    /** Conservative worst-case exposure of the scope's unsettled entries. */
    BigDecimal openExposureForConfiguration(UUID scopeId);

    /** Exposure on one race (event + scheduled start) across WIN and PLACE markets. */
    BigDecimal raceExposureForConfiguration(UUID scopeId, String eventId, Instant raceStart);

    int entriesForRace(UUID scopeId, String eventId, Instant raceStart);

    int entriesSince(UUID scopeId, Instant since);

    /** Settled profit of the scope's entries placed since {@code since} (voids contribute 0). */
    BigDecimal settledProfitSince(UUID scopeId, Instant since);

    /** Signs of settled profits (+1 win, −1 loss), most recent first; zero-profit and voided entries are skipped. */
    List<Integer> recentOutcomeSigns(UUID scopeId, Instant since, int limit);

    /** Settled WIN/LOSS signs in settlement order, used to advance staking plans. */
    List<Integer> outcomeSignsAscending(UUID scopeId);
}
