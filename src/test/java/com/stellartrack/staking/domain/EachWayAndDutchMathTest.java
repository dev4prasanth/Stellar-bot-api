package com.stellartrack.staking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stellartrack.common.domain.BetSide;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class EachWayAndDutchMathTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static StakeCalculation fixed(BetSide side, String stake, String price) {
        return StakeCalculatorChain.standard().calculate(new StakeContext(side, bd(price),
                new StakingSettings(StakingMethodType.FIXED_STAKE, bd(stake), null, null, null, null, null, null),
                null, null, bd("1.00")));
    }

    @Test
    void eachWayStakeIsPerPartWithPlaceTermsOfOneFifth() {
        // 10 each way at 6.0, 1/5 odds: place part pays at 1 + 5/5 = 2.0.
        assertThat(BetMath.eachWayPlacePrice(bd("6.0"), bd("5"))).isEqualByComparingTo("2.0");
        assertThat(BetMath.eachWayTotalStake(bd("10"))).isEqualByComparingTo("20.00");
        assertThat(BetMath.eachWayProfitIfWins(bd("10"), bd("6.0"), bd("5"))).isEqualByComparingTo("60.00");
        assertThat(BetMath.eachWayProfitIfPlaced(bd("10"), bd("6.0"), bd("5"))).isEqualByComparingTo("0.00");
        assertThat(BetMath.eachWayProfitIfPlaced(bd("10"), bd("11.0"), bd("4"))).isEqualByComparingTo("15.00");
    }

    @Test
    void eachWayCalculationCountsBothPartsAsExposure() {
        StakeCalculation ew = fixed(BetSide.BACK, "10", "6.0").forEachWay(bd("5"));
        assertThat(ew.valid()).isTrue();
        assertThat(ew.stake()).isEqualByComparingTo("10.00");
        assertThat(ew.exposure()).isEqualByComparingTo("20.00");
        assertThat(ew.estimatedGrossProfit()).isEqualByComparingTo("60.00");
        assertThat(ew.steps()).anyMatch(s -> s.label().equals("Estimated gross profit if placed only"));
    }

    @Test
    void eachWayIsBackOnlyAndNeedsPlaceTerms() {
        assertThat(fixed(BetSide.LAY, "10", "6.0").forEachWay(bd("5")).rejectionReason())
                .isEqualTo("Each-Way bets can only be Back bets");
        assertThat(fixed(BetSide.BACK, "10", "6.0").forEachWay(null).rejectionReason())
                .contains("place terms");
    }

    @Test
    void backDutchGivesEqualProfitWhicheverRunnerWins() {
        List<BigDecimal> prices = List.of(bd("4.0"), bd("12.0"));
        List<BigDecimal> stakes = DutchMath.backStakes(bd("20"), prices);
        assertThat(stakes).containsExactly(bd("15.00"), bd("5.00"));
        assertThat(DutchMath.profitIfWins(stakes.get(0), prices.get(0), bd("20"))).isEqualByComparingTo("40.00");
        assertThat(DutchMath.profitIfWins(stakes.get(1), prices.get(1), bd("20"))).isEqualByComparingTo("40.00");
        assertThat(DutchMath.impliedProbability(prices)).isLessThan(BigDecimal.ONE);
    }

    @Test
    void backDutchForATargetProfit() {
        List<BigDecimal> prices = List.of(bd("4.0"), bd("12.0"));
        // Σ(1/p) = 1/3 → profit = 2 × total, so 40 profit needs 20.
        assertThat(DutchMath.totalForTargetProfit(bd("40"), prices)).isEqualByComparingTo("20.00");
    }

    @Test
    void dutchCannotProfitWhenTheSelectionsCoverTheWholeMarket() {
        List<BigDecimal> prices = List.of(bd("2.0"), bd("2.0"), bd("3.0"));
        assertThat(DutchMath.impliedProbability(prices)).isGreaterThanOrEqualTo(BigDecimal.ONE);
        assertThatThrownBy(() -> DutchMath.totalForTargetProfit(bd("10"), prices))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot return a profit");
    }
}
