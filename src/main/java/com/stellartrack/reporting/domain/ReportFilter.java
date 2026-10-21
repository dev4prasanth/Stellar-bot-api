package com.stellartrack.reporting.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Server-side report filter. Timestamps are stored in UTC; {@code zone} only controls how buckets (day/week/
 * month) are drawn for display. Each metric documents which date it filters on (decision, placement or
 * settlement date).
 */
public record ReportFilter(Instant from, Instant to, ZoneId zone, UUID configurationId, String mode, String side,
        String marketType) {
}
