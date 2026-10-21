package com.stellartrack.order.application;

import com.stellartrack.capital.application.CapitalService;
import com.stellartrack.capital.application.CapitalService.FundsView;
import com.stellartrack.common.api.Freshness;
import com.stellartrack.common.provider.ExchangeAvailability;
import com.stellartrack.market.application.MarketCatalogService;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.market.port.MarketDataPort;
import com.stellartrack.order.control.TradingControlService;
import com.stellartrack.order.domain.OrderIntentStatus;
import com.stellartrack.order.domain.OrderPurpose;
import com.stellartrack.order.persistence.OrderIntentEntity;
import com.stellartrack.order.persistence.OrderIntentRepository;
import com.stellartrack.order.risk.EntryHistory;
import com.stellartrack.order.risk.PreTradeContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Gathers fresh, authoritative state for the risk rules immediately before a submission. The same assembly serves
 * simulated Watch bets, which supply their own funds, history and book.
 */
@Component
public class PreTradeContextFactory {

    private final MarketCatalogService catalog;
    private final MarketDataPort marketData;
    private final CapitalService capital;
    private final TradingControlService tradingControl;
    private final ExchangeAvailability exchange;
    private final OrderIntentRepository intents;
    private final OrderExposureQueries exposure;
    private final Duration marketBookMaxAge;
    private final ZoneId reportingZone;
    private final Clock clock;

    public PreTradeContextFactory(MarketCatalogService catalog, MarketDataPort marketData, CapitalService capital,
            TradingControlService tradingControl, ExchangeAvailability exchange, OrderIntentRepository intents,
            OrderExposureQueries exposure,
            @Value("${stellartrack.betfair.staleness.market-book-max-age}") Duration marketBookMaxAge,
            @Value("${stellartrack.reporting.default-time-zone}") String reportingZone, Clock clock) {
        this.catalog = catalog;
        this.marketData = marketData;
        this.capital = capital;
        this.tradingControl = tradingControl;
        this.exchange = exchange;
        this.intents = intents;
        this.exposure = exposure;
        this.marketBookMaxAge = marketBookMaxAge;
        this.reportingZone = ZoneId.of(reportingZone);
        this.clock = clock;
    }

    public PreTradeContext build(PlacementCommand command) {
        boolean available = exchange.isAvailable();
        MarketPrices prices = available
                ? marketData.getPrices(List.of(command.marketId())).stream().findFirst().orElse(null)
                : null;
        UUID configurationId = command.configuration() == null ? null : command.configuration().id();
        return assemble(command, available, prices, capital.latest(), exposure, configurationId,
                command.purpose() == OrderPurpose.ENTRY && liveEntryExists(command), false);
    }

    /**
     * Builds the context from the given sources. Live orders use account funds and real orders; Watch Mode passes
     * its virtual funds, its simulated-bet history and the book it evaluated ({@code simulated = true}).
     *
     * @param history   entries the limits and stops are counted against
     * @param scopeId   whose entries are counted in {@code history}; null counts none
     */
    public PreTradeContext assemble(PlacementCommand command, boolean exchangeAvailable, MarketPrices prices,
            Optional<FundsView> funds, EntryHistory history, UUID scopeId, boolean duplicateEntry, boolean simulated) {
        Instant now = clock.instant();
        MarketSummary market = catalog.require(command.marketId());
        Freshness freshness = prices == null
                ? Freshness.unavailable("Betfair", "No market book")
                : Freshness.evaluate(prices.asOf(), marketBookMaxAge, prices.dataDelayed(), prices.source().name(), clock);
        Instant sessionStart = command.session() == null ? now : command.session().startedAt();
        Instant dayStart = LocalDate.now(clock.withZone(reportingZone)).atStartOfDay(reportingZone).toInstant();
        List<Integer> recent = scopeId == null ? List.of() : history.recentOutcomeSigns(scopeId, sessionStart, 100);
        return new PreTradeContext(
                command,
                now,
                exchangeAvailable,
                tradingControl.isStopAllEngaged(),
                market,
                prices,
                freshness,
                funds,
                duplicateEntry,
                scopeId == null ? BigDecimal.ZERO : history.openExposureForConfiguration(scopeId),
                scopeId == null ? BigDecimal.ZERO
                        : history.raceExposureForConfiguration(scopeId, market.eventId(), market.startTime()),
                scopeId == null ? 0 : history.entriesForRace(scopeId, market.eventId(), market.startTime()),
                scopeId == null ? 0 : history.entriesSince(scopeId, dayStart),
                scopeId == null ? 0 : history.entriesSince(scopeId, sessionStart),
                scopeId == null ? BigDecimal.ZERO : history.settledProfitSince(scopeId, sessionStart),
                streak(recent, -1),
                streak(recent, 1),
                tradingControl.isWatchActive(),
                simulated);
    }

    private boolean liveEntryExists(PlacementCommand command) {
        Specification<OrderIntentEntity> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("marketId"), command.marketId()),
                cb.equal(root.get("selectionId"), command.selectionId()),
                cb.equal(root.get("side"), command.side()),
                cb.equal(root.get("purpose"), OrderPurpose.ENTRY),
                root.get("status").in(OrderIntentStatus.LIVE));
        return intents.count(spec) > 0;
    }

    /** Length of the run of {@code sign} at the start of the most-recent-first list. */
    static int streak(List<Integer> mostRecentFirst, int sign) {
        int count = 0;
        for (Integer value : mostRecentFirst) {
            if (value == null || value != sign) {
                break;
            }
            count++;
        }
        return count;
    }
}
