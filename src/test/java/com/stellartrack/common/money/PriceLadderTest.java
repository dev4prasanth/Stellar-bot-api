package com.stellartrack.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class PriceLadderTest {

    @ParameterizedTest
    @CsvSource({"1.01,true", "1.99,true", "2.00,true", "2.01,false", "2.02,true", "3.05,true", "3.07,false",
            "4.1,true", "6.2,true", "10.5,true", "20,true", "21,true", "32,true", "33,false", "55,true",
            "110,true", "1000,true", "1.00,false", "1010,false"})
    void validatesBetfairTicks(String price, boolean valid) {
        assertThat(PriceLadder.isValid(new BigDecimal(price))).isEqualTo(valid);
    }

    @Test
    void roundsInTheRequestedDirection() {
        assertThat(PriceLadder.roundToTick(new BigDecimal("2.03"), true)).isEqualByComparingTo("2.02");
        assertThat(PriceLadder.roundToTick(new BigDecimal("2.03"), false)).isEqualByComparingTo("2.04");
        assertThat(PriceLadder.roundToTick(new BigDecimal("3.97"), false)).isEqualByComparingTo("4.0");
    }

    @Test
    void offsetsAcrossBandBoundaries() {
        assertThat(PriceLadder.offset(new BigDecimal("1.99"), 2)).isEqualByComparingTo("2.02");
        assertThat(PriceLadder.offset(new BigDecimal("2.02"), -2)).isEqualByComparingTo("1.99");
        assertThat(PriceLadder.offset(new BigDecimal("1.01"), -1)).isEqualByComparingTo("1.01");
        assertThatThrownBy(() -> PriceLadder.offset(new BigDecimal("2.01"), 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsTicksBetweenPrices() {
        assertThat(PriceLadder.ticksBetween(new BigDecimal("3.00"), new BigDecimal("3.20"))).isEqualTo(4);
        assertThat(PriceLadder.ticksBetween(new BigDecimal("2.02"), new BigDecimal("1.98"))).isEqualTo(-3);
        assertThat(PriceLadder.ticksBetween(new BigDecimal("5.0"), new BigDecimal("5.0"))).isZero();
    }
}
