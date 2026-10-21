package com.stellartrack.betfair.adapter;

import com.stellartrack.betfair.api.AccountTypes.AccountStatementReport;
import com.stellartrack.betfair.api.BettingTypes.TimeRange;
import com.stellartrack.betfair.client.BetfairAccountClient;
import com.stellartrack.capital.port.AccountPort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Betfair implementation of {@link AccountPort}. */
@Component
public class BetfairAccountAdapter implements AccountPort {

    private final BetfairAccountClient client;
    private final Clock clock;

    public BetfairAccountAdapter(BetfairAccountClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public AccountFunds funds() {
        var funds = client.getAccountFunds();
        return new AccountFunds(funds.availableToBetBalance(), funds.exposure(), funds.retainedCommission(),
                funds.exposureLimit(), funds.discountRate(), funds.wallet(), clock.instant());
    }

    @Override
    public AccountDetails details() {
        var details = client.getAccountDetails();
        return new AccountDetails(details.currencyCode(), details.countryCode(), details.timezone());
    }

    @Override
    public StatementPage statement(StatementCategory category, Instant from, Instant to, int fromRecord) {
        AccountStatementReport report = client.getAccountStatement(category.name(), new TimeRange(from, to), fromRecord);
        List<StatementEntry> items = report.accountStatement() == null ? List.of() : report.accountStatement().stream()
                .map(i -> new StatementEntry(i.refId(), i.itemDate(), i.amount(), i.balance(), i.itemClass(),
                        i.itemClassData(), i.legacyData()))
                .toList();
        return new StatementPage(items, Boolean.TRUE.equals(report.moreAvailable()));
    }
}
