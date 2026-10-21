package com.stellartrack.system;

import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.capability.CapabilityRegistry;
import com.stellartrack.common.capability.CapabilityReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.SYSTEM)
@Tag(name = "System")
public class SystemController {

    public record SystemInfo(Instant serverTime, String reportingTimeZone, String schemaVersion, int pendingMigrations) {
    }

    private final CapabilityRegistry capabilities;
    private final Flyway flyway;
    private final String reportingZone;
    private final Clock clock;

    public SystemController(CapabilityRegistry capabilities, Flyway flyway,
            @Value("${stellartrack.reporting.default-time-zone}") String reportingZone, Clock clock) {
        this.capabilities = capabilities;
        this.flyway = flyway;
        this.reportingZone = reportingZone;
        this.clock = clock;
    }

    @GetMapping("/capabilities")
    @Operation(summary = "Every integration and rule-dependent feature reported as implemented, verified, disabled or blocked")
    public List<CapabilityReport> capabilities() {
        return capabilities.all();
    }

    @GetMapping("/info")
    @Operation(summary = "Server time, reporting time zone and Flyway schema version")
    public SystemInfo info() {
        var info = flyway.info();
        return new SystemInfo(clock.instant(), reportingZone,
                info.current() == null ? null : info.current().getVersion().getVersion(), info.pending().length);
    }
}
