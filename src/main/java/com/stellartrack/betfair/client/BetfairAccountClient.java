package com.stellartrack.betfair.client;

import com.stellartrack.betfair.api.AccountTypes.AccountDetailsResponse;
import com.stellartrack.betfair.api.AccountTypes.AccountFundsResponse;
import com.stellartrack.betfair.api.AccountTypes.AccountStatementReport;
import com.stellartrack.betfair.api.BetfairOperation;
import com.stellartrack.betfair.api.BettingTypes.TimeRange;
import com.stellartrack.betfair.config.BetfairProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/** Typed Betfair Accounts API operations. getAccountStatement returns at most 100 records per page. */
@Component
public class BetfairAccountClient {

    public static final int MAX_STATEMENT_PAGE = 100;

    private final BetfairJsonRpcClient rpc;
    private final BetfairProperties properties;

    public BetfairAccountClient(BetfairJsonRpcClient rpc, BetfairProperties properties) {
        this.rpc = rpc;
        this.properties = properties;
    }

    public AccountFundsResponse getAccountFunds() {
        return rpc.call(BetfairOperation.GET_ACCOUNT_FUNDS, Map.of(), new TypeReference<>() { });
    }

    public AccountDetailsResponse getAccountDetails() {
        return rpc.call(BetfairOperation.GET_ACCOUNT_DETAILS, Map.of(), new TypeReference<>() { });
    }

    /** @param includeItem Betfair IncludeItem: DEPOSITS_WITHDRAWALS or EXCHANGE */
    public AccountStatementReport getAccountStatement(String includeItem, TimeRange itemDateRange, int fromRecord) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("locale", properties.locale());
        params.put("fromRecord", fromRecord);
        params.put("recordCount", MAX_STATEMENT_PAGE);
        params.put("itemDateRange", itemDateRange);
        params.put("includeItem", includeItem);
        return rpc.call(BetfairOperation.GET_ACCOUNT_STATEMENT, params, new TypeReference<>() { });
    }
}
