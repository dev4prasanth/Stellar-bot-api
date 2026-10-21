package com.stellartrack.betfair;

import static org.assertj.core.api.Assertions.assertThat;

import com.stellartrack.betfair.adapter.BetfairOrderExecutionAdapterAccess;
import com.stellartrack.betfair.api.BettingTypes.PlaceExecutionReport;
import com.stellartrack.betfair.api.BettingTypes.PlaceInstructionReport;
import com.stellartrack.betfair.error.BetfairApiException;
import com.stellartrack.order.port.PlacementResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Provider-response normalization with test-only fixtures shaped like documented API-NG responses. */
class BetfairTranslationTest {

    @Test
    void successfulInstructionIsPlacedWithProviderIdentifiers() {
        PlaceExecutionReport report = new PlaceExecutionReport("ref", "SUCCESS", null, "1.234", List.of(
                new PlaceInstructionReport("SUCCESS", null, "EXECUTABLE", null, "31242604945",
                        Instant.parse("2026-10-12T13:00:00Z"), new BigDecimal("3.5"), new BigDecimal("4.00"))));
        PlacementResult result = BetfairOrderExecutionAdapterAccess.toPlacementResult(report);
        assertThat(result.outcome()).isEqualTo(PlacementResult.Outcome.PLACED);
        assertThat(result.betId()).isEqualTo("31242604945");
        assertThat(result.sizeMatched()).isEqualByComparingTo("4.00");
    }

    @Test
    void failureKeepsInstructionErrorCode() {
        PlaceExecutionReport report = new PlaceExecutionReport("ref", "FAILURE", "BET_ACTION_ERROR", "1.234", List.of(
                new PlaceInstructionReport("FAILURE", "INSUFFICIENT_FUNDS", null, null, null, null, null, null)));
        PlacementResult result = BetfairOrderExecutionAdapterAccess.toPlacementResult(report);
        assertThat(result.outcome()).isEqualTo(PlacementResult.Outcome.EXCHANGE_REJECTED);
        assertThat(result.providerErrorCode()).isEqualTo("BET_ACTION_ERROR");
        assertThat(result.instructionErrorCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void timeoutStatusIsAmbiguous() {
        PlaceExecutionReport report = new PlaceExecutionReport("ref", "TIMEOUT", null, "1.234", List.of());
        assertThat(BetfairOrderExecutionAdapterAccess.toPlacementResult(report).outcome())
                .isEqualTo(PlacementResult.Outcome.UNKNOWN_OUTCOME);
    }

    @Test
    void errorCodesAreClassified() {
        assertThat(BetfairApiException.fromErrorCode("INVALID_SESSION_INFORMATION", "x").kind())
                .isEqualTo(BetfairApiException.Kind.SESSION);
        assertThat(BetfairApiException.fromErrorCode("TOO_MANY_REQUESTS", "x").retryableRead()).isTrue();
        assertThat(BetfairApiException.fromErrorCode("INVALID_APP_KEY", "x").kind()).isEqualTo(BetfairApiException.Kind.ACCESS);
        assertThat(BetfairApiException.fromErrorCode("INVALID_INPUT_DATA", "x").retryableRead()).isFalse();
        assertThat(new BetfairApiException(BetfairApiException.Kind.TIMEOUT, null, "t", null).ambiguousForWrite()).isTrue();
    }
}
