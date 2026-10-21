package com.stellartrack.staking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stellartrack.staking.domain.StakingPlanStep;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StakingPlanParserTest {

    private static StakingPlanParser.ParseResult csv(String content) {
        return StakingPlanParser.parse("plan.csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesValidPlan() {
        var result = csv("step,amount,amount_type,on_win,on_loss\n1,2.00,STAKE,RESET,2\n2,4.00,STAKE,RESET,3\n3,8,LIABILITY,1,RESET\n");
        assertThat(result.valid()).isTrue();
        assertThat(result.steps()).hasSize(3);
        assertThat(result.steps().get(0).onWin()).isNull();
        assertThat(result.steps().get(0).onLoss()).isEqualTo(2);
        assertThat(result.steps().get(2).amountType()).isEqualTo(StakingPlanStep.AmountType.LIABILITY);
    }

    @Test
    void rejectsWrongHeader() {
        assertThat(csv("step,stake\n1,2\n").errors()).anyMatch(e -> e.contains("Header must be exactly"));
    }

    @Test
    void rejectsNonConsecutiveStepsAndBadReferences() {
        var result = csv("step,amount,amount_type,on_win,on_loss\n1,2.00,STAKE,RESET,5\n3,4.00,STAKE,RESET,1\n");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("consecutive"));
        assertThat(result.errors()).anyMatch(e -> e.contains("does not exist"));
    }

    @Test
    void rejectsNegativeOrOverPreciseAmounts() {
        var result = csv("step,amount,amount_type,on_win,on_loss\n1,-2.00,STAKE,RESET,RESET\n");
        assertThat(result.errors()).anyMatch(e -> e.contains("positive"));
        var precise = csv("step,amount,amount_type,on_win,on_loss\n1,2.001,STAKE,RESET,RESET\n");
        assertThat(precise.valid()).isFalse();
    }

    @Test
    void refusesMacroEnabledAndUnknownFileTypes() {
        assertThat(StakingPlanParser.parse("plan.xlsm", new byte[] {1}).errors()).anyMatch(e -> e.contains("macro"));
        assertThat(StakingPlanParser.parse("plan.exe", new byte[] {1}).valid()).isFalse();
    }
}
