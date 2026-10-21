package com.stellartrack.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CsvWriterTest {

    @Test
    void escapesAndNeutralisesFormulas() {
        String csv = new CsvWriter().row(Arrays.asList("plain", "a,b", "say \"hi\"", "=HYPERLINK(1)", "-cmd", null,
                new BigDecimal("-12.50"), true)).build();
        assertThat(csv).isEqualTo("plain,\"a,b\",\"say \"\"hi\"\"\",'=HYPERLINK(1),'-cmd,,-12.50,true\r\n");
    }
}
