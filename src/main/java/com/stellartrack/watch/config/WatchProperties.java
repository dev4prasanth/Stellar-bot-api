package com.stellartrack.watch.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Watch Mode settings.
 *
 * @param slippageTicks         ticks a simulated bet's limit price is made worse by, an allowance for delayed data
 * @param defaultCommissionRate commission base rate (percent) used when a market's description has none
 */
@ConfigurationProperties(prefix = "stellartrack.watch")
public record WatchProperties(boolean workerEnabled, Duration tickInterval,
        Duration leaseTtl, int slippageTicks, BigDecimal defaultCommissionRate) {
}
