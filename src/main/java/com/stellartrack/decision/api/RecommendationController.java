package com.stellartrack.decision.api;

import com.stellartrack.auth.security.AdminPrincipal;
import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.api.PageRequests;
import com.stellartrack.common.api.PageResponse;
import com.stellartrack.decision.application.ManualApprovalService;
import com.stellartrack.decision.application.RecommendationQueryService;
import com.stellartrack.decision.application.RecommendationQueryService.RecommendationDetail;
import com.stellartrack.decision.application.RecommendationQueryService.RecommendationFilter;
import com.stellartrack.decision.application.RecommendationQueryService.RecommendationRow;
import com.stellartrack.decision.domain.RejectionReason;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.RECOMMENDATIONS)
@Tag(name = "Recommendations & Manual-mode approvals")
public class RecommendationController {

    public record RejectRequest(@NotNull RejectionReason reasonCode, @Size(max = 1000) String note) {
    }

    public record ReasonOption(RejectionReason code, String description, boolean noteRequired) {
    }

    public record DecisionResult(UUID id, String status, String message) {
    }

    private final RecommendationQueryService queries;
    private final ManualApprovalService approvals;
    private final Clock clock;

    public RecommendationController(RecommendationQueryService queries, ManualApprovalService approvals, Clock clock) {
        this.queries = queries;
        this.approvals = approvals;
        this.clock = clock;
    }

    @GetMapping
    public PageResponse<RecommendationRow> search(
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String source,
            @RequestParam(required = false) String mode, @RequestParam(required = false) UUID configurationId,
            @RequestParam(required = false) String marketId, @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Instant end = to != null ? to : clock.instant().plusSeconds(60);
        Instant start = from != null ? from : end.minus(Duration.ofDays(7));
        var pageable = PageRequests.of(page, size, Sort.unsorted());
        return queries.search(new RecommendationFilter(start, end, status, source, mode, configurationId, marketId, outcome),
                pageable.getPageNumber(), pageable.getPageSize());
    }

    @GetMapping("/approval-queue")
    @Operation(summary = "Proposals awaiting administrator review")
    public List<RecommendationRow> queue() {
        return queries.approvalQueue();
    }

    @GetMapping("/rejection-reasons")
    public List<ReasonOption> reasons() {
        return Arrays.stream(RejectionReason.values())
                .map(r -> new ReasonOption(r, r.description(), r.noteRequired())).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Recommendation with its immutable decision snapshot, approval decision, outcome evaluation and orders")
    public RecommendationDetail detail(@PathVariable UUID id) {
        return queries.detail(id);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve: the proposal is then fully revalidated before any order is submitted")
    public DecisionResult approve(@PathVariable UUID id) {
        AdminPrincipal admin = AdminPrincipal.current();
        var r = approvals.approve(id, admin.adminId(), admin.actorId());
        return new DecisionResult(r.getId(), r.getStatus().name(),
                "Approved. Submitting with full price, funds, race-state and risk validation.");
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject with a reason code; a rejected proposal is never sent to Betfair")
    public DecisionResult reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest request) {
        AdminPrincipal admin = AdminPrincipal.current();
        var r = approvals.reject(id, request.reasonCode(), request.note(), admin.adminId(), admin.actorId());
        return new DecisionResult(r.getId(), r.getStatus().name(), "Rejected. The proposal will not be submitted.");
    }
}
