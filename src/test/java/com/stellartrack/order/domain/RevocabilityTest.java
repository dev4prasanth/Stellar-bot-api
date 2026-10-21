package com.stellartrack.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RevocabilityTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static Revocability order(String betStatus, String matched, String remaining, String cancelled,
            String lapsed, String voided, String settlementClass) {
        return Revocability.ofOrder("PLACED", betStatus, bd(matched), bd(remaining), bd(cancelled), bd(lapsed),
                bd(voided), settlementClass);
    }

    @Test
    void unmatchedAndPartiallyMatchedBetsCanBeRevoked() {
        assertThat(order("EXECUTABLE", "0", "10", "0", "0", "0", null).revocable()).isTrue();
        Revocability partial = order("EXECUTABLE", "4", "6", "0", "0", "0", null);
        assertThat(partial.revocable()).isTrue();
        assertThat(partial.reason()).isNull();
    }

    @Test
    void fullyMatchedBetCannotBeRevoked() {
        Revocability matched = order("EXECUTION_COMPLETE", "10", "0", "0", "0", "0", null);
        assertThat(matched.revocable()).isFalse();
        assertThat(matched.reason()).isEqualTo("This bet cannot be revoked because it has already been fully matched.");
    }

    @Test
    void cancelledLapsedVoidedAndSettledBetsCannotBeRevoked() {
        assertThat(order("EXECUTION_COMPLETE", "0", "0", "10", "0", "0", null).reason()).contains("already been cancelled");
        assertThat(order("EXECUTION_COMPLETE", "0", "0", "0", "10", "0", null).reason()).contains("lapsed");
        assertThat(order("EXECUTION_COMPLETE", "0", "0", "0", "0", "10", null).reason()).contains("voided");
        assertThat(order("EXECUTION_COMPLETE", "4", "0", "6", "0", "0", null).reason()).contains("no unmatched stake is left");
        assertThat(order("EXECUTION_COMPLETE", "10", "0", "0", "0", "0", "WON").reason()).contains("already been settled");
        assertThat(order("EXECUTION_COMPLETE", "10", "0", "0", "0", "0", "VOID").reason()).contains("voided");
    }

    @Test
    void betsWithoutABetfairOrderCannotBeRevoked() {
        assertThat(Revocability.ofOrder("UNKNOWN_OUTCOME", null, null, null, null, null, null, null).reason())
                .contains("still being confirmed");
        assertThat(Revocability.ofOrder("EXCHANGE_REJECTED", null, null, null, null, null, null, null).reason())
                .contains("never placed");
    }

    @Test
    void executableOrderWithNothingRemainingIsNotRevocable() {
        assertThat(Revocability.ofOrder("PLACED", "EXECUTABLE", ZERO, ZERO, ZERO, ZERO, ZERO, null).revocable()).isFalse();
    }

    @Test
    void watchBetsFollowTheirDisplayStatus() {
        assertThat(Revocability.ofWatchBet("PENDING").revocable()).isTrue();
        assertThat(Revocability.ofWatchBet("PARTIALLY_MATCHED").revocable()).isTrue();
        assertThat(Revocability.ofWatchBet("MATCHED").reason()).contains("fully matched");
        assertThat(Revocability.ofWatchBet("WON").reason()).contains("settled");
        assertThat(Revocability.ofWatchBet("VOID").revocable()).isFalse();
    }
}
