package com.stellartrack.capital.api;

import com.stellartrack.capital.application.CapitalService;
import com.stellartrack.capital.application.CapitalService.FundsView;
import com.stellartrack.capital.application.StatementSyncService;
import com.stellartrack.capital.persistence.AccountFundsSnapshotEntity;
import com.stellartrack.capital.persistence.AccountStatementItemEntity;
import com.stellartrack.capital.persistence.AccountStatementItemRepository;
import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.api.Freshness;
import com.stellartrack.common.api.PageRequests;
import com.stellartrack.common.api.PageResponse;
import com.stellartrack.common.capability.Capability;
import com.stellartrack.common.capability.CapabilityRegistry;
import com.stellartrack.common.capability.CapabilityReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.CAPITAL)
@Tag(name = "Capital & Payments")
public class CapitalController {

    public record FundsPoint(Instant capturedAt, BigDecimal availableToBet, BigDecimal exposure) {
    }

    public record StatementItemResponse(String refId, Instant itemDate, BigDecimal amount, BigDecimal balance,
            String itemClass, AccountStatementItemEntity.Category category, String itemClassData, String legacyData) {
    }

    public record PaymentsInfo(CapabilityReport capability, String accountManagementUrl) {
    }

    private final CapitalService capitalService;
    private final StatementSyncService statementSync;
    private final AccountStatementItemRepository statementItems;
    private final CapabilityRegistry capabilities;
    private final String accountManagementUrl;
    private final Clock clock;

    public CapitalController(CapitalService capitalService, StatementSyncService statementSync,
            AccountStatementItemRepository statementItems, CapabilityRegistry capabilities,
            @Value("${stellartrack.betfair.account-management-url}") String accountManagementUrl, Clock clock) {
        this.capitalService = capitalService;
        this.statementSync = statementSync;
        this.statementItems = statementItems;
        this.capabilities = capabilities;
        this.accountManagementUrl = accountManagementUrl;
        this.clock = clock;
    }

    @GetMapping("/funds")
    @Operation(summary = "Latest stored account funds with freshness (account-wide, not per configuration)")
    public FundsView funds() {
        return capitalService.latest().orElse(new FundsView(null, null, null, null, null, null, null,
                Freshness.unavailable("Betfair getAccountFunds", "No funds snapshot yet")));
    }

    @PostMapping("/funds/refresh")
    @Operation(summary = "Fetch account funds from Betfair now")
    public FundsView refreshFunds() {
        return capitalService.refresh();
    }

    @GetMapping("/funds/history")
    public List<FundsPoint> history(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Instant end = to != null ? to : clock.instant();
        Instant start = from != null ? from : end.minus(Duration.ofDays(30));
        return capitalService.history(start, end).stream().map(CapitalController::point).toList();
    }

    @GetMapping("/statement")
    @Operation(summary = "Synchronized Betfair account statement (deposits/withdrawals are not betting P&L)")
    public PageResponse<StatementItemResponse> statement(
            @RequestParam(required = false) AccountStatementItemEntity.Category category,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        var pageable = PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "itemDate"));
        Page<AccountStatementItemEntity> result = category == null
                ? statementItems.findAll(pageable)
                : statementItems.findByCategory(category, pageable);
        return PageResponse.from(result, e -> new StatementItemResponse(e.getRefId(), e.getItemDate(), e.getAmount(),
                e.getBalance(), e.getItemClass(), e.getCategory(), e.getItemClassData(), e.getLegacyData()));
    }

    @PostMapping("/statement/sync")
    public int syncStatement() {
        return statementSync.sync();
    }

    @GetMapping("/payments")
    @Operation(summary = "Payment initiation status and the official account-management link")
    public PaymentsInfo payments() {
        return new PaymentsInfo(capabilities.get(Capability.PAYMENT_INITIATION), accountManagementUrl);
    }

    private static FundsPoint point(AccountFundsSnapshotEntity s) {
        return new FundsPoint(s.getCapturedAt(), s.getAvailableToBet(), s.getExposure());
    }
}
