package com.stellartrack.capital.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Exchange account funds, details and statement. Implemented by the Betfair adapter. */
public interface AccountPort {

    AccountFunds funds();

    AccountDetails details();

    StatementPage statement(StatementCategory category, Instant from, Instant to, int fromRecord);

    record AccountFunds(BigDecimal availableToBet, BigDecimal exposure, BigDecimal retainedCommission,
            BigDecimal exposureLimit, BigDecimal discountRate, String wallet, Instant asOf) {
    }

    record AccountDetails(String currencyCode, String countryCode, String timezone) {
    }

    enum StatementCategory {
        DEPOSITS_WITHDRAWALS,
        EXCHANGE
    }

    record StatementEntry(String refId, Instant itemDate, BigDecimal amount, BigDecimal balance, String itemClass,
            Map<String, String> itemClassData, Map<String, Object> legacyData) {
    }

    record StatementPage(List<StatementEntry> items, boolean moreAvailable) {
    }
}
