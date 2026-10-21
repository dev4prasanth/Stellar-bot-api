package com.stellartrack.watch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.market.domain.MarketPrices;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaperFillEngineTest {

    private static MarketPrices.PriceLevel level(String price, String size) {
        return new MarketPrices.PriceLevel(new BigDecimal(price), new BigDecimal(size));
    }

    private static MarketPrices.RunnerPrices runner(String status, List<MarketPrices.PriceLevel> back,
            List<MarketPrices.PriceLevel> lay) {
        return new MarketPrices.RunnerPrices(7L, status, null, null, null, back, lay, null);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void limitPriceIsWorseForTheBettorBySlippage() {
        assertThat(PaperFillEngine.limitPrice(BetSide.BACK, bd("5.50"), 1)).isEqualByComparingTo("5.4");
        assertThat(PaperFillEngine.limitPrice(BetSide.LAY, bd("5.50"), 1)).isEqualByComparingTo("5.6");
        assertThat(PaperFillEngine.limitPrice(BetSide.BACK, bd("5.50"), 0)).isEqualByComparingTo("5.5");
    }

    @Test
    void backFillsAtOfferedPricesAtOrAboveTheLimitCappedBySizeShown() {
        var r = runner("ACTIVE", List.of(level("5.6", "10"), level("5.5", "15"), level("5.4", "100")), List.of());
        List<PaperFillEngine.Fill> fills = PaperFillEngine.match(BetSide.BACK, bd("5.5"), bd("40"), r, Map.of());
        assertThat(fills).containsExactly(new PaperFillEngine.Fill(bd("5.60"), bd("10.00")),
                new PaperFillEngine.Fill(bd("5.50"), bd("15.00")));
        assertThat(PaperFillEngine.averagePrice(fills)).isEqualByComparingTo("5.54");
    }

    @Test
    void layFillsAtOfferedPricesAtOrBelowTheLimit() {
        var r = runner("ACTIVE", List.of(), List.of(level("4.1", "8"), level("4.0", "5"), level("4.2", "50")));
        List<PaperFillEngine.Fill> fills = PaperFillEngine.match(BetSide.LAY, bd("4.1"), bd("20"), r, Map.of());
        assertThat(fills).extracting(PaperFillEngine.Fill::price).containsExactly(bd("4.00"), bd("4.10"));
        assertThat(fills).extracting(PaperFillEngine.Fill::size).containsExactly(bd("5.00"), bd("8.00"));
    }

    @Test
    void noFillWhenThePriceIsWorseThanTheLimitOrRunnerInactive() {
        var r = runner("ACTIVE", List.of(level("5.0", "100")), List.of());
        assertThat(PaperFillEngine.match(BetSide.BACK, bd("5.5"), bd("10"), r, Map.of())).isEmpty();
        var removed = runner("REMOVED", List.of(level("6.0", "100")), List.of());
        assertThat(PaperFillEngine.match(BetSide.BACK, bd("5.5"), bd("10"), removed, Map.of())).isEmpty();
    }

    @Test
    void sizeAlreadyTakenAtAPriceIsNotMatchedAgain() {
        var r = runner("ACTIVE", List.of(level("5.5", "15")), List.of());
        List<PaperFillEngine.Fill> fills = PaperFillEngine.match(BetSide.BACK, bd("5.5"), bd("40"), r,
                Map.of(bd("5.50"), bd("10.00")));
        assertThat(fills).containsExactly(new PaperFillEngine.Fill(bd("5.50"), bd("5.00")));
    }
}
