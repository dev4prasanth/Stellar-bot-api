package com.stellartrack.order.risk;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Runs every risk rule and returns all violations, so the administrator sees each reason at once. */
@Component
public class PreTradeValidator {

    private final List<RiskRule> rules;

    public PreTradeValidator() {
        this(StandardRiskRules.all());
    }

    PreTradeValidator(List<RiskRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<RiskViolation> validate(PreTradeContext context) {
        return rules.stream().map(rule -> rule.check(context)).flatMap(Optional::stream).toList();
    }
}
