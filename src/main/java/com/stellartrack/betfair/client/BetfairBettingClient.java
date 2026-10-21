package com.stellartrack.betfair.client;

import com.stellartrack.betfair.api.BetfairOperation;
import com.stellartrack.betfair.api.BettingTypes.CancelExecutionReport;
import com.stellartrack.betfair.api.BettingTypes.CancelInstruction;
import com.stellartrack.betfair.api.BettingTypes.ClearedOrderSummaryReport;
import com.stellartrack.betfair.api.BettingTypes.CurrentOrderSummaryReport;
import com.stellartrack.betfair.api.BettingTypes.EventResult;
import com.stellartrack.betfair.api.BettingTypes.MarketBook;
import com.stellartrack.betfair.api.BettingTypes.MarketCatalogue;
import com.stellartrack.betfair.api.BettingTypes.MarketFilter;
import com.stellartrack.betfair.api.BettingTypes.MarketVersion;
import com.stellartrack.betfair.api.BettingTypes.PlaceExecutionReport;
import com.stellartrack.betfair.api.BettingTypes.PlaceInstruction;
import com.stellartrack.betfair.api.BettingTypes.PriceProjection;
import com.stellartrack.betfair.api.BettingTypes.TimeRange;
import com.stellartrack.betfair.config.BetfairProperties;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * Typed Betfair Betting API operations. Request limits (documented "market data request limits"):
 * listMarketCatalogue weight × maxResults ≤ 200; listMarketBook weight × markets ≤ 200;
 * listCurrentOrders/listClearedOrders return at most 1000 records per page.
 */
@Component
public class BetfairBettingClient {

    public static final String HORSE_RACING_EVENT_TYPE_ID = "7";
    /** MARKET_DESCRIPTION (1) + RUNNER_METADATA (1); EVENT, RUNNER_DESCRIPTION, MARKET_START_TIME weigh 0. */
    public static final int CATALOGUE_WEIGHT_PER_MARKET = 2;
    /** EX_BEST_OFFERS with default depth weighs 5 per market. */
    public static final int BOOK_WEIGHT_PER_MARKET = 5;
    public static final int MAX_REQUEST_WEIGHT = 200;
    public static final int MAX_ORDER_PAGE = 1000;

    private final BetfairJsonRpcClient rpc;
    private final BetfairProperties properties;

    public BetfairBettingClient(BetfairJsonRpcClient rpc, BetfairProperties properties) {
        this.rpc = rpc;
        this.properties = properties;
    }

    public List<EventResult> listEvents(MarketFilter filter) {
        return rpc.call(BetfairOperation.LIST_EVENTS, params("filter", filter, "locale", properties.locale()),
                new TypeReference<>() { });
    }

    public List<MarketCatalogue> listMarketCatalogue(MarketFilter filter, int maxResults) {
        return rpc.call(BetfairOperation.LIST_MARKET_CATALOGUE, params(
                "filter", filter,
                "marketProjection", List.of("EVENT", "MARKET_START_TIME", "MARKET_DESCRIPTION", "RUNNER_DESCRIPTION",
                        "RUNNER_METADATA"),
                "sort", "FIRST_TO_START",
                "maxResults", maxResults,
                "locale", properties.locale()), new TypeReference<>() { });
    }

    public List<MarketBook> listMarketBook(Collection<String> marketIds) {
        return rpc.call(BetfairOperation.LIST_MARKET_BOOK, params(
                "marketIds", marketIds,
                "priceProjection", new PriceProjection(Set.of("EX_BEST_OFFERS"), true),
                "locale", properties.locale()), new TypeReference<>() { });
    }

    public PlaceExecutionReport placeOrders(String marketId, List<PlaceInstruction> instructions, String customerRef,
            Long marketVersion, String customerStrategyRef) {
        Map<String, Object> params = params("marketId", marketId, "instructions", instructions, "customerRef", customerRef,
                "customerStrategyRef", customerStrategyRef);
        if (marketVersion != null) {
            params.put("marketVersion", new MarketVersion(marketVersion));
        }
        return rpc.call(BetfairOperation.PLACE_ORDERS, params, new TypeReference<>() { });
    }

    public CancelExecutionReport cancelOrders(String marketId, List<CancelInstruction> instructions, String customerRef) {
        return rpc.call(BetfairOperation.CANCEL_ORDERS,
                params("marketId", marketId, "instructions", instructions, "customerRef", customerRef),
                new TypeReference<>() { });
    }

    public CurrentOrderSummaryReport listCurrentOrders(Set<String> betIds, Set<String> marketIds,
            Set<String> customerOrderRefs, int fromRecord) {
        return rpc.call(BetfairOperation.LIST_CURRENT_ORDERS, params(
                "betIds", emptyToNull(betIds),
                "marketIds", emptyToNull(marketIds),
                "customerOrderRefs", emptyToNull(customerOrderRefs),
                "orderProjection", "ALL",
                "fromRecord", fromRecord,
                "recordCount", MAX_ORDER_PAGE), new TypeReference<>() { });
    }

    public ClearedOrderSummaryReport listClearedOrders(String betStatus, TimeRange settledDateRange, Set<String> betIds,
            String groupBy, int fromRecord) {
        return rpc.call(BetfairOperation.LIST_CLEARED_ORDERS, params(
                "betStatus", betStatus,
                "eventTypeIds", Set.of(HORSE_RACING_EVENT_TYPE_ID),
                "betIds", emptyToNull(betIds),
                "settledDateRange", settledDateRange,
                "groupBy", groupBy,
                "includeItemDescription", false,
                "fromRecord", fromRecord,
                "recordCount", MAX_ORDER_PAGE), new TypeReference<>() { });
    }

    private static Set<String> emptyToNull(Set<String> values) {
        return values == null || values.isEmpty() ? null : values;
    }

    /** Builds a parameter map, omitting null values (Betfair treats absent and null differently for some fields). */
    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }
}
