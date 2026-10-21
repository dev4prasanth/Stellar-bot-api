package com.stellartrack.automation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param restartPolicy what happens to running configurations when the application restarts. Only
 *                      {@code NEVER_RESUME} is supported: an administrator must start them again.
 */
@ConfigurationProperties(prefix = "stellartrack.automation")
public record AutomationProperties(boolean workerEnabled, Duration tickInterval, Duration leaseTtl,
        RestartPolicy restartPolicy) {

    public enum RestartPolicy {
        NEVER_RESUME
    }
}
