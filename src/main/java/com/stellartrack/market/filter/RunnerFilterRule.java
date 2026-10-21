package com.stellartrack.market.filter;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** One active filter rule. New rule kinds are added by implementing this interface. */
public interface RunnerFilterRule {

    FilterField field();

    FilterOutcome.RuleResult evaluate(RunnerContext context);

    static RunnerFilterRule numeric(FilterField field, NumericRange range, Function<RunnerContext, Optional<BigDecimal>> extractor) {
        return new RunnerFilterRule() {
            @Override
            public FilterField field() {
                return field;
            }

            @Override
            public FilterOutcome.RuleResult evaluate(RunnerContext context) {
                Optional<BigDecimal> value = extractor.apply(context);
                if (value.isEmpty()) {
                    return unavailable(field, range.describe());
                }
                boolean pass = range.contains(value.get());
                return new FilterOutcome.RuleResult(field,
                        pass ? FilterOutcome.Status.PASSED : FilterOutcome.Status.FAILED,
                        value.get().toPlainString(), range.describe(),
                        pass ? null : field.label() + " " + value.get().toPlainString() + " is outside " + range.describe());
            }
        };
    }

    static RunnerFilterRule oneOf(FilterField field, Set<String> allowed, Function<RunnerContext, Optional<String>> extractor) {
        Set<String> normalized = allowed.stream().map(v -> v.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        return new RunnerFilterRule() {
            @Override
            public FilterField field() {
                return field;
            }

            @Override
            public FilterOutcome.RuleResult evaluate(RunnerContext context) {
                String requirement = "one of " + allowed;
                Optional<String> value = extractor.apply(context);
                if (value.isEmpty()) {
                    return unavailable(field, requirement);
                }
                boolean pass = normalized.contains(value.get().toUpperCase(Locale.ROOT));
                return new FilterOutcome.RuleResult(field, pass ? FilterOutcome.Status.PASSED : FilterOutcome.Status.FAILED,
                        value.get(), requirement, pass ? null : field.label() + " '" + value.get() + "' is not " + requirement);
            }
        };
    }

    static RunnerFilterRule contains(FilterField field, String needle, Function<RunnerContext, Optional<String>> extractor) {
        return new RunnerFilterRule() {
            @Override
            public FilterField field() {
                return field;
            }

            @Override
            public FilterOutcome.RuleResult evaluate(RunnerContext context) {
                String requirement = "contains '" + needle + "'";
                Optional<String> value = extractor.apply(context);
                if (value.isEmpty()) {
                    return unavailable(field, requirement);
                }
                boolean pass = value.get().toUpperCase(Locale.ROOT).contains(needle.toUpperCase(Locale.ROOT));
                return new FilterOutcome.RuleResult(field, pass ? FilterOutcome.Status.PASSED : FilterOutcome.Status.FAILED,
                        value.get(), requirement, pass ? null : field.label() + " '" + value.get() + "' does not " + requirement);
            }
        };
    }

    /** An active rule whose data is missing must exclude the runner (fail closed). */
    static FilterOutcome.RuleResult unavailable(FilterField field, String requirement) {
        String why = field.source() == FilterField.DataSource.UNAVAILABLE
                ? field.label() + " has no verified data source"
                : field.label() + " was not supplied by " + field.source() + " (" + field.sourceField() + ")";
        return new FilterOutcome.RuleResult(field, FilterOutcome.Status.DATA_UNAVAILABLE, null, requirement, why);
    }
}
