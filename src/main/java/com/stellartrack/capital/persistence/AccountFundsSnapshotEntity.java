package com.stellartrack.capital.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "account_funds_snapshot")
public class AccountFundsSnapshotEntity {

    @Id
    private UUID id;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "currency_code")
    private String currencyCode;

    @Column(name = "available_to_bet", nullable = false)
    private BigDecimal availableToBet;

    @Column(nullable = false)
    private BigDecimal exposure;

    @Column(name = "retained_commission")
    private BigDecimal retainedCommission;

    @Column(name = "exposure_limit")
    private BigDecimal exposureLimit;

    @Column(name = "discount_rate")
    private BigDecimal discountRate;

    private String wallet;

    protected AccountFundsSnapshotEntity() {
    }

    public AccountFundsSnapshotEntity(Instant capturedAt, String currencyCode, BigDecimal availableToBet,
            BigDecimal exposure, BigDecimal retainedCommission, BigDecimal exposureLimit, BigDecimal discountRate,
            String wallet) {
        this.id = UUID.randomUUID();
        this.capturedAt = capturedAt;
        this.currencyCode = currencyCode;
        this.availableToBet = availableToBet;
        this.exposure = exposure;
        this.retainedCommission = retainedCommission;
        this.exposureLimit = exposureLimit;
        this.discountRate = discountRate;
        this.wallet = wallet;
    }

    public UUID getId() { return id; }
    public Instant getCapturedAt() { return capturedAt; }
    public String getCurrencyCode() { return currencyCode; }
    public BigDecimal getAvailableToBet() { return availableToBet; }
    public BigDecimal getExposure() { return exposure; }
    public BigDecimal getRetainedCommission() { return retainedCommission; }
    public BigDecimal getExposureLimit() { return exposureLimit; }
    public BigDecimal getDiscountRate() { return discountRate; }
    public String getWallet() { return wallet; }
}
