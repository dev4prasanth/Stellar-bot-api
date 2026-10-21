package com.stellartrack.market.filter;

import jakarta.validation.constraints.AssertTrue;
import java.math.BigDecimal;

/** Inclusive numeric range; either bound may be open. */
public record NumericRange(BigDecimal min, BigDecimal max) {

    public boolean contains(BigDecimal value) {
        return (min == null || value.compareTo(min) >= 0) && (max == null || value.compareTo(max) <= 0);
    }

    public boolean isActive() {
        return min != null || max != null;
    }

    @AssertTrue(message = "min must not exceed max")
    public boolean isOrdered() {
        return min == null || max == null || min.compareTo(max) <= 0;
    }

    public String describe() {
        if (min != null && max != null) {
            return min.toPlainString() + "–" + max.toPlainString();
        }
        return min != null ? "≥ " + min.toPlainString() : "≤ " + max.toPlainString();
    }
}
