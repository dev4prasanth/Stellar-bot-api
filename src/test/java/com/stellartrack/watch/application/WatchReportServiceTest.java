package com.stellartrack.watch.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class WatchReportServiceTest {

    private static List<BigDecimal> results(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }

    @Test
    void longestStreaksCountConsecutiveWinsAndLosses() {
        List<BigDecimal> sequence = results("10", "5", "-3", "-4", "-1", "8");
        assertThat(WatchReportService.longestStreak(sequence, 1)).isEqualTo(2);
        assertThat(WatchReportService.longestStreak(sequence, -1)).isEqualTo(3);
    }

    @Test
    void maxDrawdownIsTheLargestFallFromAPeak() {
        assertThat(WatchReportService.maxDrawdown(results("10", "5", "-3", "-4", "-1", "8"))).isEqualByComparingTo("8");
        assertThat(WatchReportService.maxDrawdown(results("-5", "2"))).isEqualByComparingTo("5");
        assertThat(WatchReportService.maxDrawdown(List.of())).isEqualByComparingTo("0");
    }
}
