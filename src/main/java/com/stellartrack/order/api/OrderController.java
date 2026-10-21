package com.stellartrack.order.api;

import com.stellartrack.auth.security.AdminPrincipal;
import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.api.PageRequests;
import com.stellartrack.common.api.PageResponse;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.order.application.DutchTicketService;
import com.stellartrack.order.application.ManualTicketService;
import com.stellartrack.order.application.ManualTicketService.ConfirmRequest;
import com.stellartrack.order.application.ManualTicketService.TicketRequest;
import com.stellartrack.order.application.ManualTicketService.TicketReview;
import com.stellartrack.order.application.OrderCancellationService;
import com.stellartrack.order.application.OrderQueryService;
import com.stellartrack.order.application.OrderQueryService.OrderDetail;
import com.stellartrack.order.application.OrderQueryService.OrderFilter;
import com.stellartrack.order.application.OrderQueryService.OrderRow;
import com.stellartrack.order.application.OrderReconciliationService;
import com.stellartrack.order.application.PlacementOutcome;
import com.stellartrack.order.persistence.ReconciliationCheckpointEntity;
import com.stellartrack.order.port.CancellationResult;
import com.stellartrack.order.port.PersistenceType;
import com.stellartrack.staking.domain.StakingSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.ORDERS)
@Tag(name = "Orders and manual betting")
public class OrderController {

    public record ReviewRequest(@NotBlank String marketId, long selectionId, @NotNull BetSide side,
            @DecimalMin("1.01") @DecimalMax("1000") BigDecimal price, UUID configurationId,
            @Valid StakingSettings staking, PersistenceType persistenceType) {
    }

    public record PlaceRequest(@NotBlank String reviewToken,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String idempotencyKey, Boolean acceptDelayedData,
            @Valid StakingSettings staking) {
    }

    public record CancelRequest(@DecimalMin("0.01") BigDecimal sizeReduction) {
    }

    public record DutchRevokeResponse(List<DutchTicketService.LegRevoke> legs) {
    }

    private final ManualTicketService tickets;
    private final DutchTicketService dutch;
    private final OrderQueryService queries;
    private final OrderCancellationService cancellation;
    private final OrderReconciliationService reconciliation;
    private final Clock clock;

    public OrderController(ManualTicketService tickets, DutchTicketService dutch, OrderQueryService queries,
            OrderCancellationService cancellation, OrderReconciliationService reconciliation, Clock clock) {
        this.tickets = tickets;
        this.dutch = dutch;
        this.queries = queries;
        this.cancellation = cancellation;
        this.reconciliation = reconciliation;
        this.clock = clock;
    }

    @PostMapping("/review")
    @Operation(summary = "Review Bet: authoritative stake/liability/profit and every pre-trade check, with a signed review token")
    public TicketReview review(@Valid @RequestBody ReviewRequest request) {
        return tickets.review(new TicketRequest(request.marketId(), request.selectionId(), request.side(), request.price(),
                request.configurationId(), request.staking(), request.persistenceType()), AdminPrincipal.current().actorId());
    }

    @PostMapping("/place")
    @Operation(summary = "Confirm & Place Bet: submits exactly the reviewed terms after full backend revalidation")
    public ResponseEntity<PlacementOutcome> place(@Valid @RequestBody PlaceRequest request) {
        PlacementOutcome outcome = tickets.confirm(new ConfirmRequest(request.reviewToken(), request.idempotencyKey(),
                Boolean.TRUE.equals(request.acceptDelayedData()), request.staking()), AdminPrincipal.current().actorId());
        HttpStatus status = switch (outcome.status()) {
            case PLACED, UNKNOWN_OUTCOME -> HttpStatus.OK;
            case RECONFIRMATION_REQUIRED -> HttpStatus.CONFLICT;
            case RISK_REJECTED, EXCHANGE_REJECTED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case TECHNICAL_FAILURE -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(outcome);
    }

    @PostMapping("/dutch/review")
    @Operation(summary = "Review a Back Dutch bet on a WIN market: the stake per runner for equal profit, every leg's "
            + "pre-trade checks and group checks (minimum stake, profit possible, funds). Nothing is placed.")
    public DutchTicketService.DutchReview reviewDutch(@RequestBody DutchTicketService.DutchRequest request) {
        return dutch.review(request, AdminPrincipal.current().actorId());
    }

    @PostMapping("/dutch/place")
    @Operation(summary = "Place every reviewed leg of a Dutch bet under one Dutch group (each leg fully revalidated)")
    public DutchTicketService.DutchOutcome placeDutch(@RequestBody DutchTicketService.DutchConfirm request) {
        return dutch.place(request, AdminPrincipal.current().actorId());
    }

    @PostMapping("/dutch/{groupId}/revoke")
    @Operation(summary = "Revoke the unmatched stake of every leg of a Dutch bet (each re-checked with Betfair first)")
    public DutchRevokeResponse revokeDutch(@PathVariable UUID groupId) {
        return new DutchRevokeResponse(dutch.revokeUnmatched(groupId, AdminPrincipal.current().actorId()));
    }

    @GetMapping
    @Operation(summary = "Bet history: intention, submission, matching and settlement as separate stages. open=true lists "
            + "running bets only (any date); origin=AUTOMATIC limits to bot bets, MANUAL to Race Market tickets.")
    public PageResponse<OrderRow> search(
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String mode, @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID configurationId, @RequestParam(required = false) String marketId,
            @RequestParam(required = false) String settlementClass, @RequestParam(required = false) String origin,
            @RequestParam(defaultValue = "false") boolean open,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Instant end = to != null ? to : clock.instant().plus(Duration.ofMinutes(1));
        Instant start = from != null ? from : end.minus(Duration.ofDays(30));
        var pageable = PageRequests.of(page, size, org.springframework.data.domain.Sort.unsorted());
        return queries.search(new OrderFilter(start, end, mode, status, configurationId, marketId, settlementClass, origin, open),
                pageable.getPageNumber(), pageable.getPageSize());
    }

    @GetMapping("/{intentId}")
    public OrderDetail detail(@PathVariable UUID intentId) {
        return queries.detail(intentId);
    }

    @PostMapping("/bets/{betId}/cancel")
    @Operation(summary = "Revoke the unmatched portion (or reduce it by sizeReduction). The bet's status is re-read from "
            + "Betfair first; a fully matched, cancelled, lapsed, voided or settled bet is refused (422).")
    public CancellationResult cancel(@PathVariable String betId, @Valid @RequestBody(required = false) CancelRequest request) {
        return cancellation.cancel(betId, request == null ? null : request.sizeReduction(), AdminPrincipal.current().actorId());
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Reconciliation job checkpoints")
    public List<ReconciliationCheckpointEntity> reconciliationStatus() {
        return reconciliation.checkpoints();
    }

    @PostMapping("/reconciliation/run")
    @Operation(summary = "Run open-order and ambiguous-submission reconciliation now")
    public List<ReconciliationCheckpointEntity> runReconciliation() {
        reconciliation.reconcileOpenAndAmbiguous();
        return reconciliation.checkpoints();
    }
}
