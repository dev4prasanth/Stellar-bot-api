package com.stellartrack.market.application;

import com.stellartrack.common.provider.ExchangeAvailability;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketResult;
import com.stellartrack.market.domain.MarketResultCapturedEvent;
import com.stellartrack.market.persistence.MarketEntity;
import com.stellartrack.market.persistence.MarketRepository;
import com.stellartrack.market.persistence.MarketRunnerEntity;
import com.stellartrack.market.persistence.MarketRunnerRepository;
import com.stellartrack.market.port.MarketDataPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Captures official runner results once a market is CLOSED, from the provider's own market book. Results are
 * the authoritative input for post-race selection-outcome evaluation (never for realized P&L, which comes from
 * settlement records).
 */
@Service
public class MarketResultService {

    private static final Logger log = LoggerFactory.getLogger(MarketResultService.class);
    private static final Duration MIN_AGE = Duration.ofMinutes(5);
    private static final Duration MAX_AGE = Duration.ofDays(7);
    private static final int BATCH = 40;

    private final MarketDataPort marketData;
    private final MarketRepository markets;
    private final MarketRunnerRepository runners;
    private final ExchangeAvailability exchange;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate tx;

    public MarketResultService(MarketDataPort marketData, MarketRepository markets, MarketRunnerRepository runners,
            ExchangeAvailability exchange, ApplicationEventPublisher events, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.marketData = marketData;
        this.markets = markets;
        this.runners = runners;
        this.exchange = exchange;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${stellartrack.reconciliation.market-results-interval}", initialDelay = 60_000)
    public void captureClosedMarkets() {
        if (!exchange.isAvailable()) {
            return;
        }
        Instant now = clock.instant();
        List<MarketEntity> pending = markets.findAwaitingResult(now.minus(MIN_AGE), now.minus(MAX_AGE), PageRequest.of(0, BATCH));
        if (pending.isEmpty()) {
            return;
        }
        try {
            recordBooks(marketData.getPrices(pending.stream().map(MarketEntity::getMarketId).toList()));
        } catch (RuntimeException e) {
            log.warn("Market result capture failed: {}", e.getMessage());
        }
    }

    /** Stores each market book's status (and, once CLOSED, its result), one transaction per market. */
    public void recordBooks(List<MarketPrices> books) {
        books.forEach(book -> tx.executeWithoutResult(status -> apply(book)));
    }

    /** Stores the latest status and, once CLOSED, the official runner results. Runs inside the caller's transaction. */
    void apply(MarketPrices book) {
        Instant now = clock.instant();
        markets.findById(book.marketId()).ifPresent(market -> {
            market.updateStatus(book.status(), book.inPlay(), book.numberOfWinners(), now);
            for (MarketPrices.RunnerPrices rp : book.runners()) {
                runners.findById(new MarketRunnerEntity.Key(book.marketId(), rp.selectionId()))
                        .ifPresent(r -> r.updateStatus(rp.status(), rp.adjustmentFactor(), rp.removalDate(), now));
            }
            if (MarketPrices.STATUS_CLOSED.equals(book.status()) && market.getResultCapturedAt() == null) {
                market.markResultCaptured(now);
                events.publishEvent(new MarketResultCapturedEvent(book.marketId()));
            }
        });
    }

    @Transactional(readOnly = true)
    public Optional<MarketResult> result(String marketId) {
        return markets.findById(marketId)
                .filter(m -> m.getResultCapturedAt() != null)
                .map(m -> {
                    Map<Long, MarketResult.RunnerResult> results = new LinkedHashMap<>();
                    runners.findByMarketId(marketId).forEach(r -> results.put(r.getId().selectionId(),
                            new MarketResult.RunnerResult(r.getStatus(), r.getAdjustmentFactor(), r.getRemovalDate())));
                    return new MarketResult(marketId, m.getMarketType(), m.getNumberOfWinners(), results,
                            m.getResultCapturedAt());
                });
    }
}
