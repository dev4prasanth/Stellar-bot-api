package com.stellartrack.audit.api;

import com.stellartrack.audit.application.AuditQueryService;
import com.stellartrack.audit.application.AuditQueryService.AuditFilter;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditOutcome;
import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.api.PageRequests;
import com.stellartrack.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.AUDIT)
@Tag(name = "Audit Logs")
public class AuditController {

    private final AuditQueryService queryService;

    public AuditController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @Operation(summary = "Search the append-only audit trail")
    public PageResponse<AuditEventResponse> search(
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        var filter = new AuditFilter(action, outcome, entityType, entityId, correlationId, from, to);
        var pageable = PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt", "id"));
        return PageResponse.from(queryService.search(filter, pageable), AuditEventResponse::from);
    }
}
