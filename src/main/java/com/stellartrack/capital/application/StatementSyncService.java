package com.stellartrack.capital.application;

import com.stellartrack.capital.persistence.AccountStatementItemEntity;
import com.stellartrack.capital.persistence.AccountStatementItemRepository;
import com.stellartrack.capital.port.AccountPort;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.common.provider.ExchangeAvailability;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Synchronizes the Betfair account statement. Deposits/withdrawals and exchange items are fetched separately
 * (IncludeItem DEPOSITS_WITHDRAWALS / EXCHANGE) so funds movements are never mistaken for betting returns.
 * Items are upserted by refId; a one-day overlap makes the sync idempotent across restarts.
 */
@Service
public class StatementSyncService {

    private static final Logger log = LoggerFactory.getLogger(StatementSyncService.class);
    private static final Duration INITIAL_LOOKBACK = Duration.ofDays(90);
    private static final Duration OVERLAP = Duration.ofDays(1);
    private static final int MAX_PAGES = 50;

    private final AccountPort account;
    private final AccountStatementItemRepository items;
    private final ExchangeAvailability exchange;
    private final JsonCodec json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public StatementSyncService(AccountPort account, AccountStatementItemRepository items, ExchangeAvailability exchange,
            JsonCodec json, Clock clock, PlatformTransactionManager txManager) {
        this.account = account;
        this.items = items;
        this.exchange = exchange;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${stellartrack.reconciliation.statement-interval}", initialDelay = 45_000)
    public void scheduledSync() {
        if (!exchange.isAvailable()) {
            return;
        }
        try {
            sync();
        } catch (RuntimeException e) {
            log.warn("Account statement sync failed: {}", e.getMessage());
        }
    }

    public int sync() {
        return syncCategory(AccountPort.StatementCategory.DEPOSITS_WITHDRAWALS, AccountStatementItemEntity.Category.DEPOSIT_WITHDRAWAL)
                + syncCategory(AccountPort.StatementCategory.EXCHANGE, AccountStatementItemEntity.Category.EXCHANGE);
    }

    private int syncCategory(AccountPort.StatementCategory providerCategory, AccountStatementItemEntity.Category category) {
        Instant now = clock.instant();
        Instant from = items.latestItemDate(category).map(d -> d.minus(OVERLAP)).orElse(now.minus(INITIAL_LOOKBACK));
        int stored = 0;
        int fromRecord = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            AccountPort.StatementPage result = account.statement(providerCategory, from, now, fromRecord);
            tx.executeWithoutResult(status -> result.items().forEach(item -> upsert(item, category, now)));
            stored += result.items().size();
            if (!result.moreAvailable() || result.items().isEmpty()) {
                break;
            }
            fromRecord += result.items().size();
        }
        return stored;
    }

    private void upsert(AccountPort.StatementEntry entry, AccountStatementItemEntity.Category category, Instant now) {
        AccountStatementItemEntity entity = items.findById(entry.refId())
                .orElseGet(() -> new AccountStatementItemEntity(entry.refId()));
        entity.update(entry.itemDate(), entry.amount(), entry.balance(), entry.itemClass(), category,
                json.write(entry.itemClassData() == null ? Map.of() : entry.itemClassData()),
                json.write(entry.legacyData() == null ? Map.of() : entry.legacyData()), now);
        items.save(entity);
    }
}
