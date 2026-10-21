package com.stellartrack.capital.application;

import com.stellartrack.common.capability.Capability;
import com.stellartrack.common.capability.CapabilityProvider;
import com.stellartrack.common.capability.CapabilityReport;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Betfair's published Exchange APIs offer no deposit or withdrawal operation, so payment initiation is not
 * implemented. Transactions remain visible through the account statement, and the UI links to the official
 * account-management site.
 */
@Component
public class PaymentsCapabilityProvider implements CapabilityProvider {

    @Override
    public List<CapabilityReport> capabilities() {
        return List.of(CapabilityReport.blocked(Capability.PAYMENT_INITIATION,
                "No verified, authorized Betfair API supports deposits or withdrawals; use the official account site",
                "An authorized payments API for this account"));
    }
}
