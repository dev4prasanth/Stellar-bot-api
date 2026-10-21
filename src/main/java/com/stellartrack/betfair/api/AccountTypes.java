package com.stellartrack.betfair.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Raw Betfair Accounts API (AccountAPING/v1.0) wire types. Only fields the application uses are mapped. */
public final class AccountTypes {

    private AccountTypes() {
    }

    public record AccountFundsResponse(BigDecimal availableToBetBalance, BigDecimal exposure,
            BigDecimal retainedCommission, BigDecimal exposureLimit, BigDecimal discountRate, Integer pointsBalance,
            String wallet) {
    }

    /** Personal fields (name, etc.) are deliberately not mapped. */
    public record AccountDetailsResponse(String currencyCode, String countryCode, String timezone) {
    }

    public record StatementItem(String refId, Instant itemDate, BigDecimal amount, BigDecimal balance,
            String itemClass, Map<String, String> itemClassData, Map<String, Object> legacyData) {
    }

    public record AccountStatementReport(List<StatementItem> accountStatement, Boolean moreAvailable) {
    }
}
