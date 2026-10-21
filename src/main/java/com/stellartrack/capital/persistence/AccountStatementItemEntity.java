package com.stellartrack.capital.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A Betfair account statement item, keyed by the provider's refId so re-syncs are idempotent. */
@Entity
@Table(name = "account_statement_item")
public class AccountStatementItemEntity {

    public enum Category {
        DEPOSIT_WITHDRAWAL,
        EXCHANGE,
        OTHER
    }

    @Id
    @Column(name = "ref_id")
    private String refId;

    @Column(name = "item_date", nullable = false)
    private Instant itemDate;

    @Column(nullable = false)
    private BigDecimal amount;

    private BigDecimal balance;

    @Column(name = "item_class")
    private String itemClass;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "item_class_data", nullable = false)
    private String itemClassData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "legacy_data", nullable = false)
    private String legacyData;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    protected AccountStatementItemEntity() {
    }

    public AccountStatementItemEntity(String refId) {
        this.refId = refId;
    }

    public void update(Instant itemDate, BigDecimal amount, BigDecimal balance, String itemClass, Category category,
            String itemClassData, String legacyData, Instant syncedAt) {
        this.itemDate = itemDate;
        this.amount = amount;
        this.balance = balance;
        this.itemClass = itemClass;
        this.category = category;
        this.itemClassData = itemClassData;
        this.legacyData = legacyData;
        this.syncedAt = syncedAt;
    }

    public String getRefId() { return refId; }
    public Instant getItemDate() { return itemDate; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalance() { return balance; }
    public String getItemClass() { return itemClass; }
    public Category getCategory() { return category; }
    public String getItemClassData() { return itemClassData; }
    public String getLegacyData() { return legacyData; }
    public Instant getSyncedAt() { return syncedAt; }
}
