package com.stellartrack.order.risk;

import java.util.Optional;

/** One independent pre-trade check. New checks are added as new implementations (open/closed). */
public interface RiskRule {

    String name();

    Optional<RiskViolation> check(PreTradeContext context);
}
