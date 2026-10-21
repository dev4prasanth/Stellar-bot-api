package com.stellartrack.watch.persistence;

import com.stellartrack.common.domain.TradingMode;
import com.stellartrack.watch.domain.CapitalSource;
import com.stellartrack.watch.domain.WatchSessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One Watch period, from Start Watch to Stop Watch, with its virtual capital and simulation settings. */
@Entity
@Table(name = "watch_session")
public class WatchSessionEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WatchSessionStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "started_by", nullable = false)
    private String startedBy;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "stopped_by")
    private String stoppedBy;

    @Column(name = "stop_reason")
    private String stopReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_trading_mode", nullable = false)
    private TradingMode previousTradingMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "resumed_trading_mode")
    private TradingMode resumedTradingMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "capital_source", nullable = false)
    private CapitalSource capitalSource;

    @Column(name = "starting_capital", nullable = false)
    private BigDecimal startingCapital;

    @Column(name = "currency_code")
    private String currencyCode;

    @Column(name = "real_funds_snapshot_id")
    private UUID realFundsSnapshotId;

    @Column(name = "commission_discount_rate", nullable = false)
    private BigDecimal commissionDiscountRate;

    @Column(name = "default_commission_rate", nullable = false)
    private BigDecimal defaultCommissionRate;

    @Column(name = "fill_model_version", nullable = false)
    private String fillModelVersion;

    @Column(name = "slippage_ticks", nullable = false)
    private int slippageTicks;

    @Column(name = "settlement_rule_version", nullable = false)
    private String settlementRuleVersion;

    @Column(name = "last_tick_at")
    private Instant lastTickAt;

    @Column(name = "last_error")
    private String lastError;

    private String notes;

    protected WatchSessionEntity() {
    }

    public static WatchSessionEntity start(String name, String actor, Instant now, TradingMode previousMode,
            CapitalSource source, BigDecimal startingCapital, String currencyCode, UUID realFundsSnapshotId,
            BigDecimal discountRate, BigDecimal defaultCommissionRate, String fillModelVersion, int slippageTicks,
            String settlementRuleVersion, String notes) {
        WatchSessionEntity s = new WatchSessionEntity();
        s.id = UUID.randomUUID();
        s.name = name;
        s.status = WatchSessionStatus.ACTIVE;
        s.startedAt = now;
        s.startedBy = actor;
        s.previousTradingMode = previousMode;
        s.capitalSource = source;
        s.startingCapital = startingCapital;
        s.currencyCode = currencyCode;
        s.realFundsSnapshotId = realFundsSnapshotId;
        s.commissionDiscountRate = discountRate;
        s.defaultCommissionRate = defaultCommissionRate;
        s.fillModelVersion = fillModelVersion;
        s.slippageTicks = slippageTicks;
        s.settlementRuleVersion = settlementRuleVersion;
        s.notes = notes;
        return s;
    }

    /** Fixes the live mode that was active before Watch, once known. */
    public void previousTradingMode(TradingMode mode) {
        this.previousTradingMode = mode;
    }

    public void stop(TradingMode resumed, String reason, String actor, Instant now) {
        this.status = WatchSessionStatus.STOPPED;
        this.resumedTradingMode = resumed;
        this.stopReason = truncate(reason, 500);
        this.stoppedBy = actor;
        this.stoppedAt = now;
    }

    public void ticked(Instant now, String error) {
        this.lastTickAt = now;
        this.lastError = truncate(error, 1000);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public WatchSessionStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public String getStartedBy() { return startedBy; }
    public Instant getStoppedAt() { return stoppedAt; }
    public String getStoppedBy() { return stoppedBy; }
    public String getStopReason() { return stopReason; }
    public TradingMode getPreviousTradingMode() { return previousTradingMode; }
    public TradingMode getResumedTradingMode() { return resumedTradingMode; }
    public CapitalSource getCapitalSource() { return capitalSource; }
    public BigDecimal getStartingCapital() { return startingCapital; }
    public String getCurrencyCode() { return currencyCode; }
    public UUID getRealFundsSnapshotId() { return realFundsSnapshotId; }
    public BigDecimal getCommissionDiscountRate() { return commissionDiscountRate; }
    public BigDecimal getDefaultCommissionRate() { return defaultCommissionRate; }
    public String getFillModelVersion() { return fillModelVersion; }
    public int getSlippageTicks() { return slippageTicks; }
    public String getSettlementRuleVersion() { return settlementRuleVersion; }
    public Instant getLastTickAt() { return lastTickAt; }
    public String getLastError() { return lastError; }
    public String getNotes() { return notes; }

    public boolean isActive() {
        return status == WatchSessionStatus.ACTIVE;
    }
}
