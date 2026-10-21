package com.stellartrack.capital.application;

import com.stellartrack.capital.persistence.AccountFundsSnapshotEntity;
import com.stellartrack.capital.persistence.AccountFundsSnapshotRepository;
import com.stellartrack.capital.port.AccountPort;
import com.stellartrack.common.api.Freshness;
import com.stellartrack.common.provider.ExchangeAvailability;
import com.stellartrack.staking.application.BankResolver;
import com.stellartrack.staking.domain.StakingSettings;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account funds from the exchange, stored as timestamped snapshots. Funds are shared across all
 * configurations; internal allocations are limits applied by the application, never separate wallets.
 */
@Service
public class CapitalService implements BankResolver {

    private static final Logger log = LoggerFactory.getLogger(CapitalService.class);

    public record FundsView(String currencyCode, BigDecimal availableToBet, BigDecimal exposure,
            BigDecimal retainedCommission, BigDecimal exposureLimit, BigDecimal discountRate, String wallet,
            Freshness freshness) {
    }

    private final AccountPort account;
    private final AccountFundsSnapshotRepository snapshots;
    private final ExchangeAvailability exchange;
    private final Duration fundsMaxAge;
    private final Clock clock;
    private final AtomicReference<String> currency = new AtomicReference<>();

    public CapitalService(AccountPort account, AccountFundsSnapshotRepository snapshots, ExchangeAvailability exchange,
            @Value("${stellartrack.betfair.staleness.funds-max-age}") Duration fundsMaxAge, Clock clock) {
        this.account = account;
        this.snapshots = snapshots;
        this.exchange = exchange;
        this.fundsMaxAge = fundsMaxAge;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${stellartrack.reconciliation.funds-interval}", initialDelay = 20_000)
    public void scheduledRefresh() {
        if (!exchange.isAvailable()) {
            return;
        }
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("Account funds refresh failed: {}", e.getMessage());
        }
    }

    /** Fetches funds from the exchange now and stores a snapshot. */
    @Transactional
    public FundsView refresh() {
        if (currency.get() == null) {
            currency.set(account.details().currencyCode());
        }
        AccountPort.AccountFunds funds = account.funds();
        AccountFundsSnapshotEntity saved = snapshots.save(new AccountFundsSnapshotEntity(funds.asOf(), currency.get(),
                funds.availableToBet(), funds.exposure(), funds.retainedCommission(), funds.exposureLimit(),
                funds.discountRate(), funds.wallet()));
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public Optional<FundsView> latest() {
        return snapshots.findFirstByOrderByCapturedAtDesc().map(this::toView);
    }

    @Transactional(readOnly = true)
    public List<AccountFundsSnapshotEntity> history(Instant from, Instant to) {
        return snapshots.findByCapturedAtBetweenOrderByCapturedAtAsc(from, to);
    }

    /** A stored funds snapshot and its id, so a copy of the balance can reference exactly what it copied. */
    public record FundsSnapshot(UUID id, FundsView funds) {
    }

    /** Latest snapshot, refreshed from the exchange first when it is not fresh. Empty when no fresh funds exist. */
    @Transactional
    public Optional<FundsSnapshot> freshSnapshot() {
        if (freshFunds().isEmpty() && exchange.isAvailable()) {
            refresh();
        }
        return snapshots.findFirstByOrderByCapturedAtDesc()
                .map(e -> new FundsSnapshot(e.getId(), toView(e)))
                .filter(s -> s.funds().freshness().state() == Freshness.State.LIVE);
    }

    /** Latest funds only if fresh enough to base a real-money decision on. */
    @Transactional(readOnly = true)
    public Optional<FundsView> freshFunds() {
        return latest().filter(view -> view.freshness().state() == Freshness.State.LIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal resolve(StakingSettings settings, BigDecimal allocatedCapital) {
        return BankResolver.byBasis(settings, allocatedCapital,
                () -> freshFunds().map(FundsView::availableToBet).orElse(null));
    }

    private FundsView toView(AccountFundsSnapshotEntity e) {
        return new FundsView(e.getCurrencyCode(), e.getAvailableToBet(), e.getExposure(), e.getRetainedCommission(),
                e.getExposureLimit(), e.getDiscountRate(), e.getWallet(),
                Freshness.evaluate(e.getCapturedAt(), fundsMaxAge, false, "Betfair getAccountFunds", clock));
    }
}
