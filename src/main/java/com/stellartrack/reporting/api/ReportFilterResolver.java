package com.stellartrack.reporting.api;

import com.stellartrack.common.exception.BusinessRuleException;
import com.stellartrack.reporting.domain.ReportFilter;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves report filters from request parameters. Dates may be given as local dates in the display zone
 * ({@code fromDate}/{@code toDate}, inclusive) or as UTC instants; the default period is the last 30 days.
 */
@Component
public class ReportFilterResolver {

    private static final Duration MAX_RANGE = Duration.ofDays(400);

    private final ZoneId defaultZone;
    private final Clock clock;

    public ReportFilterResolver(@Value("${stellartrack.reporting.default-time-zone}") String defaultZone, Clock clock) {
        this.defaultZone = ZoneId.of(defaultZone);
        this.clock = clock;
    }

    public ReportFilter resolve(Instant from, Instant to, LocalDate fromDate, LocalDate toDate, String zone,
            UUID configurationId, String mode, String side, String marketType) {
        ZoneId zoneId;
        try {
            zoneId = zone == null || zone.isBlank() ? defaultZone : ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new BusinessRuleException("INVALID_TIME_ZONE", "Unknown time zone: " + zone);
        }
        Instant end = toDate != null ? toDate.plusDays(1).atStartOfDay(zoneId).toInstant()
                : to != null ? to : clock.instant();
        Instant start = fromDate != null ? fromDate.atStartOfDay(zoneId).toInstant()
                : from != null ? from : end.minus(Duration.ofDays(30));
        if (!start.isBefore(end)) {
            throw new BusinessRuleException("INVALID_RANGE", "The start of the period must be before its end");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new BusinessRuleException("RANGE_TOO_LARGE", "Choose a period of at most 400 days");
        }
        return new ReportFilter(start, end, zoneId, configurationId, blankToNull(mode), blankToNull(side),
                blankToNull(marketType));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
