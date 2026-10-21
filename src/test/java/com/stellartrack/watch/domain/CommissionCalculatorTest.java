package com.stellartrack.watch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommissionCalculatorTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void chargesBaseRateLessDiscountOnNetWinningsOnly() {
        assertThat(CommissionCalculator.commission(bd("176.80"), bd("5"), bd("0"))).isEqualByComparingTo("8.84");
        assertThat(CommissionCalculator.commission(bd("100"), bd("5"), bd("20"))).isEqualByComparingTo("4.00");
        assertThat(CommissionCalculator.commission(bd("-25"), bd("5"), bd("0"))).isEqualByComparingTo("0");
        assertThat(CommissionCalculator.commission(bd("100"), null, bd("0"))).isEqualByComparingTo("0");
    }

    @Test
    void attributesCommissionToWinningBetsInProportion() {
        List<BigDecimal> shares = CommissionCalculator.attribute(bd("3.00"), List.of(bd("40"), bd("-10"), bd("20")));
        assertThat(shares).containsExactly(bd("2.00"), bd("0.00"), bd("1.00"));
        assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("3.00");
    }

    @Test
    void roundingRemainderGoesToTheLastWinningBet() {
        List<BigDecimal> shares = CommissionCalculator.attribute(bd("1.00"), List.of(bd("10"), bd("10"), bd("10")));
        assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1.00");
        assertThat(shares.get(2)).isEqualByComparingTo("0.34");
    }
}
