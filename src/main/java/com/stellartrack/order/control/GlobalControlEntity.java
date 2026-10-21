package com.stellartrack.order.control;

import com.stellartrack.common.domain.TradingMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Singleton row holding the global trading controls: the Stop All switch and the trading mode. */
@Entity
@Table(name = "global_control")
public class GlobalControlEntity {

    public static final short SINGLETON_ID = 1;

    @Id
    private short id;

    @Column(name = "stop_all", nullable = false)
    private boolean stopAll;

    @Column(name = "stop_all_reason")
    private String stopAllReason;

    @Column(name = "changed_by")
    private String changedBy;

    @Column(name = "changed_at")
    private Instant changedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trading_mode", nullable = false, length = 10)
    private TradingMode tradingMode;

    @Column(name = "trading_mode_changed_by")
    private String tradingModeChangedBy;

    @Column(name = "trading_mode_changed_at")
    private Instant tradingModeChangedAt;

    protected GlobalControlEntity() {
    }

    public void set(boolean value, String reason, String actor, Instant now) {
        this.stopAll = value;
        this.stopAllReason = reason;
        this.changedBy = actor;
        this.changedAt = now;
    }

    public void setTradingMode(TradingMode mode, String actor, Instant now) {
        this.tradingMode = mode;
        this.tradingModeChangedBy = actor;
        this.tradingModeChangedAt = now;
    }

    public boolean isStopAll() { return stopAll; }
    public String getStopAllReason() { return stopAllReason; }
    public String getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
    public TradingMode getTradingMode() { return tradingMode; }
    public String getTradingModeChangedBy() { return tradingModeChangedBy; }
    public Instant getTradingModeChangedAt() { return tradingModeChangedAt; }
}
