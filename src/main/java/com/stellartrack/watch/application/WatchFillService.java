package com.stellartrack.watch.application;

import com.stellartrack.common.events.UiNotification;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.port.MarketDataPort;
import com.stellartrack.order.port.PersistenceType;
import com.stellartrack.watch.domain.PaperFillEngine;
import com.stellartrack.watch.persistence.WatchBetEntity;
import com.stellartrack.watch.persistence.WatchBetFillEntity;
import com.stellartrack.watch.persistence.WatchBetFillRepository;
import com.stellartrack.watch.persistence.WatchBetRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Simulated matching of Watch bets against market prices ({@link PaperFillEngine}), at placement and on every
 * re-check while a bet is open. Matching ends when:
 * <ul>
 *   <li>the runner is removed (unmatched part voided);</li>
 *   <li>the market turns in-play, for LAPSE and MARKET_ON_CLOSE bets (SP conversion is not simulated);</li>
 *   <li>the market closes, for any bet.</li>
 * </ul>
 */
@Service
public class WatchFillService {

    private static final Logger log = LoggerFactory.getLogger(WatchFillService.class);

    private final WatchBetRepository bets;
    private final WatchBetFillRepository fills;
    private final MarketDataPort marketData;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WatchFillService(WatchBetRepository bets, WatchBetFillRepository fills, MarketDataPort marketData,
            ApplicationEventPublisher events, PlatformTransactionManager transactionManager, Clock clock) {
        this.bets = bets;
        this.fills = fills;
        this.marketData = marketData;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Matches what the book allows now. Runs inside the caller's transaction. */
    public void match(WatchBetEntity bet, MarketPrices book, String reason) {
        Optional<MarketPrices.RunnerPrices> runner = book.runner(bet.getSelectionId());
        String closeReason = closeReason(bet, book, runner);
        if (closeReason == null) {
            List<WatchBetFillEntity> previous = fills.findByBetIdOrderBySeqAsc(bet.getId());
            Map<BigDecimal, BigDecimal> consumed = previous.stream().collect(Collectors.toMap(WatchBetFillEntity::getPrice,
                    WatchBetFillEntity::getSize, BigDecimal::add));
            List<PaperFillEngine.Fill> fresh = PaperFillEngine.match(bet.getSide(), bet.getLimitPrice(), bet.getSizeRemaining(),
                    runner.orElse(null), consumed);
            if (!fresh.isEmpty()) {
                Instant now = clock.instant();
                int seq = previous.size();
                for (PaperFillEngine.Fill fill : fresh) {
                    fills.save(new WatchBetFillEntity(bet.getId(), ++seq, fill.price(), fill.size(), reason, book.asOf(), now));
                }
                List<PaperFillEngine.Fill> all = new ArrayList<>(previous.stream()
                        .map(f -> new PaperFillEngine.Fill(f.getPrice(), f.getSize())).toList());
                all.addAll(fresh);
                bet.filled(fresh.stream().map(PaperFillEngine.Fill::size).reduce(BigDecimal.ZERO, BigDecimal::add),
                        PaperFillEngine.averagePrice(all), now);
            }
        } else {
            bet.closeMatching(runner.map(r -> !r.isActive()).orElse(false), closeReason, clock.instant());
        }
        events.publishEvent(UiNotification.of("watch", bet.getSessionId(), Map.of("bet", bet.getId().toString(),
                "matching", bet.getMatchingStatus().name())));
    }

    /** Re-checks every open Watch bet against fresh books: fills when the price allows, closes when matching ends. */
    public void recheckOpenBets() {
        List<WatchBetEntity> open = bets.findByClosedAtIsNull();
        if (open.isEmpty()) {
            return;
        }
        Map<String, MarketPrices> books = marketData.getPrices(open.stream().map(WatchBetEntity::getMarketId).distinct().toList())
                .stream().collect(Collectors.toMap(MarketPrices::marketId, Function.identity(), (a, b) -> a));
        for (WatchBetEntity bet : open) {
            MarketPrices book = books.get(bet.getMarketId());
            if (book == null) {
                continue;
            }
            try {
                tx.executeWithoutResult(status -> bets.lockById(bet.getId()).filter(b -> b.getClosedAt() == null)
                        .ifPresent(fresh -> match(fresh, book, WatchBetFillEntity.PRICE_CROSSED)));
            } catch (RuntimeException e) {
                log.warn("Watch bet {} re-check failed: {}", bet.getId(), e.getMessage());
            }
        }
    }

    /** Why matching must end now, or null while the bet may still match. */
    private static String closeReason(WatchBetEntity bet, MarketPrices book, Optional<MarketPrices.RunnerPrices> runner) {
        if (runner.isPresent() && !runner.get().isActive()) {
            return "Runner " + runner.get().status() + ": unmatched part voided";
        }
        if (MarketPrices.STATUS_CLOSED.equals(book.status())) {
            return "Market closed: unmatched part lapsed";
        }
        if (book.inPlay() && !PersistenceType.PERSIST.name().equals(bet.getPersistenceType())) {
            return PersistenceType.MARKET_ON_CLOSE.name().equals(bet.getPersistenceType())
                    ? "Market turned in-play: SP conversion is not simulated, unmatched part lapsed"
                    : "Market turned in-play: unmatched part lapsed";
        }
        return null;
    }
}
