package com.stellartrack.support;

import com.stellartrack.capital.port.AccountPort;
import com.stellartrack.common.provider.ExchangeAvailability;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSearch;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.market.domain.RacingEvent;
import com.stellartrack.market.port.MarketDataPort;
import com.stellartrack.order.port.CancellationResult;
import com.stellartrack.order.port.OrderExecutionPort;
import com.stellartrack.order.port.PlaceOrderCommand;
import com.stellartrack.order.port.PlacementResult;
import com.stellartrack.order.port.ProviderMarketSettlement;
import com.stellartrack.order.port.ProviderOrderState;
import com.stellartrack.order.port.ProviderSettlement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * TEST-ONLY test double for the exchange ports. Never registered outside the test source set; production code
 * talks to Betfair through the real adapters.
 */
public class FakeExchange implements MarketDataPort, OrderExecutionPort, AccountPort, ExchangeAvailability {

    public final Map<String, MarketSummary> markets = new ConcurrentHashMap<>();
    public final Map<String, MarketPrices> prices = new ConcurrentHashMap<>();
    public final List<PlaceOrderCommand> placed = new CopyOnWriteArrayList<>();
    public final Map<String, ProviderOrderState> currentOrders = new ConcurrentHashMap<>();
    public final List<ProviderSettlement> cleared = new CopyOnWriteArrayList<>();
    public final List<ProviderMarketSettlement> clearedMarkets = new CopyOnWriteArrayList<>();
    public volatile Function<PlaceOrderCommand, PlacementResult> placementBehaviour = this::acceptAll;
    public volatile BigDecimal available = new BigDecimal("1000.00");
    private final AtomicInteger betIds = new AtomicInteger(1000);

    public void reset() {
        markets.clear();
        prices.clear();
        placed.clear();
        currentOrders.clear();
        cleared.clear();
        clearedMarkets.clear();
        placementBehaviour = this::acceptAll;
        available = new BigDecimal("1000.00");
    }

    public PlacementResult acceptAll(PlaceOrderCommand c) {
        String betId = String.valueOf(betIds.incrementAndGet());
        currentOrders.put(betId, new ProviderOrderState(betId, c.marketId(), c.selectionId(), c.handicap(), c.side(),
                c.price(), c.size(), c.price(), c.size(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                true, Instant.now(), Instant.now(), c.persistenceType().name(), "LIMIT", c.customerOrderRef(),
                c.customerStrategyRef(), ProviderOrderState.Source.POLL));
        return new PlacementResult(PlacementResult.Outcome.PLACED, "SUCCESS", null, "SUCCESS", null, betId, Instant.now(),
                c.price(), c.size(), "EXECUTION_COMPLETE", null);
    }

    /** Places the order without matching anything: it rests unmatched (EXECUTABLE) on the exchange. */
    public PlacementResult acceptUnmatched(PlaceOrderCommand c) {
        String betId = String.valueOf(betIds.incrementAndGet());
        currentOrders.put(betId, new ProviderOrderState(betId, c.marketId(), c.selectionId(), c.handicap(), c.side(),
                c.price(), c.size(), null, BigDecimal.ZERO, c.size(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                false, Instant.now(), null, c.persistenceType().name(), "LIMIT", c.customerOrderRef(),
                c.customerStrategyRef(), ProviderOrderState.Source.POLL));
        return new PlacementResult(PlacementResult.Outcome.PLACED, "SUCCESS", null, "SUCCESS", null, betId, Instant.now(),
                null, BigDecimal.ZERO, "EXECUTABLE", null);
    }

    /** The exchange matches the whole remaining stake of a resting order. */
    public void matchFully(String betId) {
        ProviderOrderState o = currentOrders.get(betId);
        currentOrders.put(betId, new ProviderOrderState(o.betId(), o.marketId(), o.selectionId(), o.handicap(), o.side(),
                o.priceRequested(), o.sizeRequested(), o.priceRequested(), o.sizeRequested(), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, o.placedAt(), Instant.now(), o.persistenceType(),
                o.orderType(), o.customerOrderRef(), o.customerStrategyRef(), ProviderOrderState.Source.POLL));
    }

    public String lastBetIdFor(String customerOrderRef) {
        return currentOrders.values().stream().filter(o -> customerOrderRef.equals(o.customerOrderRef()))
                .map(ProviderOrderState::betId).findFirst().orElseThrow();
    }

    // ---- ExchangeAvailability
    @Override
    public Optional<String> unavailableReason() {
        return Optional.empty();
    }

    // ---- MarketDataPort
    @Override
    public List<RacingEvent> listEvents(MarketSearch search) {
        return List.of();
    }

    @Override
    public List<MarketSummary> listMarkets(MarketSearch search) {
        return markets.values().stream()
                .filter(m -> search.marketIds().isEmpty() || search.marketIds().contains(m.marketId())).toList();
    }

    @Override
    public List<MarketPrices> getPrices(Collection<String> marketIds) {
        List<MarketPrices> result = new ArrayList<>();
        marketIds.forEach(id -> Optional.ofNullable(prices.get(id)).map(p -> new MarketPrices(p.marketId(), p.status(),
                p.inPlay(), p.dataDelayed(), p.betDelaySeconds(), p.numberOfWinners(), p.numberOfActiveRunners(),
                p.totalMatched(), p.version(), Instant.now(), p.source(), p.runners())).ifPresent(result::add));
        return result;
    }

    // ---- OrderExecutionPort
    @Override
    public PlacementResult place(PlaceOrderCommand command) {
        placed.add(command);
        return placementBehaviour.apply(command);
    }

    @Override
    public CancellationResult cancel(String marketId, String betId, BigDecimal sizeReduction, String customerRef) {
        return new CancellationResult(CancellationResult.Outcome.SUCCESS, sizeReduction, null, Instant.now());
    }

    @Override
    public List<ProviderOrderState> currentOrders(Set<String> betIds, Set<String> marketIds, Set<String> customerOrderRefs) {
        return currentOrders.values().stream()
                .filter(o -> betIds.isEmpty() || betIds.contains(o.betId()))
                .filter(o -> marketIds.isEmpty() || marketIds.contains(o.marketId()))
                .filter(o -> customerOrderRefs.isEmpty() || customerOrderRefs.contains(o.customerOrderRef()))
                .toList();
    }

    @Override
    public List<ProviderSettlement> clearedOrders(String betStatus, Instant from, Instant to, Set<String> betIds) {
        return cleared.stream().filter(s -> s.betStatus().equals(betStatus)).toList();
    }

    @Override
    public List<ProviderMarketSettlement> clearedMarkets(Instant from, Instant to) {
        return List.copyOf(clearedMarkets);
    }

    // ---- AccountPort
    @Override
    public AccountFunds funds() {
        return new AccountFunds(available, BigDecimal.ZERO, BigDecimal.ZERO, null, null, "UK", Instant.now());
    }

    @Override
    public AccountDetails details() {
        return new AccountDetails("GBP", "GB", "Europe/London");
    }

    @Override
    public StatementPage statement(StatementCategory category, Instant from, Instant to, int fromRecord) {
        return new StatementPage(List.of(), false);
    }
}
