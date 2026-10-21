package com.stellartrack.watch.application;

import com.stellartrack.capital.application.CapitalService.FundsView;
import com.stellartrack.common.api.Freshness;
import com.stellartrack.common.money.MoneyPolicy;
import com.stellartrack.watch.persistence.WatchCapitalEntryEntity;
import com.stellartrack.watch.persistence.WatchCapitalEntryRepository;
import com.stellartrack.watch.persistence.WatchSessionEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The virtual capital of a Watch session: an append-only ledger (start, settled bets, commission). Nothing here
 * reads or moves real account money.
 * <ul>
 *   <li>Current capital = starting capital + every settled amount.</li>
 *   <li>Available to bet = current capital − open simulated exposure (the same worst case used for live orders).</li>
 * </ul>
 */
@Service
public class WatchCapitalService {

    public record CapitalView(BigDecimal startingCapital, BigDecimal currentCapital, BigDecimal openExposure,
            BigDecimal availableToBet, BigDecimal profitLoss, String currencyCode) {
    }

    private final WatchCapitalEntryRepository entries;
    private final WatchExposureQueries exposure;
    private final Clock clock;

    public WatchCapitalService(WatchCapitalEntryRepository entries, WatchExposureQueries exposure, Clock clock) {
        this.entries = entries;
        this.exposure = exposure;
        this.clock = clock;
    }

    @Transactional
    public void open(WatchSessionEntity session) {
        append(session.getId(), WatchCapitalEntryEntity.Type.START, session.getStartingCapital(), null, null,
                "Starting capital (" + session.getCapitalSource() + ")");
    }

    /** Appends an amount (positive or negative) to the ledger; zero amounts are not recorded. */
    @Transactional
    public void append(UUID sessionId, WatchCapitalEntryEntity.Type type, BigDecimal amount, UUID betId, String marketId,
            String description) {
        if (type != WatchCapitalEntryEntity.Type.START && amount.signum() == 0) {
            return;
        }
        WatchCapitalEntryEntity last = entries.findFirstBySessionIdOrderBySeqDesc(sessionId).orElse(null);
        int seq = last == null ? 1 : last.getSeq() + 1;
        BigDecimal balance = last == null ? MoneyPolicy.zero() : last.getBalanceAfter();
        entries.save(new WatchCapitalEntryEntity(sessionId, seq, type, MoneyPolicy.amount(amount),
                MoneyPolicy.amount(balance.add(amount)), betId, marketId, description, clock.instant()));
    }

    @Transactional(readOnly = true)
    public CapitalView view(WatchSessionEntity session) {
        BigDecimal current = entries.findFirstBySessionIdOrderBySeqDesc(session.getId())
                .map(WatchCapitalEntryEntity::getBalanceAfter).orElse(session.getStartingCapital());
        BigDecimal open = MoneyPolicy.amount(exposure.openExposureForSession(session.getId()));
        return new CapitalView(session.getStartingCapital(), current, open, MoneyPolicy.amount(current.subtract(open)),
                MoneyPolicy.amount(current.subtract(session.getStartingCapital())), session.getCurrencyCode());
    }

    /**
     * The virtual capital in the shape the funds rule checks. It is computed from the ledger at this instant, so it
     * is always current (LIVE freshness); the discount rate is the one captured when the session started.
     */
    @Transactional(readOnly = true)
    public FundsView funds(WatchSessionEntity session) {
        CapitalView capital = view(session);
        Instant now = clock.instant();
        return new FundsView(session.getCurrencyCode(), capital.availableToBet(), capital.openExposure(), null, null,
                session.getCommissionDiscountRate(), "WATCH", new Freshness(Freshness.State.LIVE, now, "Watch capital ledger", null));
    }

    @Transactional(readOnly = true)
    public List<WatchCapitalEntryEntity> ledger(UUID sessionId) {
        return entries.findBySessionIdOrderBySeqAsc(sessionId);
    }
}
