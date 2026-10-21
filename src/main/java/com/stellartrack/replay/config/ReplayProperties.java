package com.stellartrack.replay.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stellartrack.replay")
public record ReplayProperties(String storageDir, List<String> allowedExtensions) {
}
