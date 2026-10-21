package com.stellartrack.order.persistence;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.order.port.ProviderOrderState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A Betfair order (betId) attributed to one of this application's intents. Quantities follow provider
 * semantics; matched size never decreases except through provider-reported voids.
 */
@Entity
@Table(name = "bet_order")
public class BetOrderEntity {

    public static final String EXECUTABLE = "EXECUTABLE";
    public static final String EXECUTION_COMPLETE = "EXECUTION_COMPLETE";

    @Id
    @Column(name = "bet_id")
    private String betId;

    @Column(name = "intent_id")
    private UUID intentId;

    @Column(name = "market_id", nullable = false)
    private String marketId;

    @Column(name = "selection_id", nullable = false)
    private long selectionId;

    @Column(nullable = false)
    private BigDecimal handicap;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false)
    private BetSide side;

    @Column(name = "order_type")
    private String orderType;

    @Column(name = "persistence_type")
    private String persistenceType;

    @Column(name = "price_requested")
    private BigDecimal priceRequested;

    @Column(name = "size_requested")
    private BigDecimal sizeRequested;

    @Column(name = "average_price_matched")
    private BigDecimal averagePriceMatched;

    @Column(name = "size_matched", nullable = false)
    private BigDecimal sizeMatched = BigDecimal.ZERO;

    @Column(name = "size_remaining", nullable = false)
    private BigDecimal sizeRemaining = BigDecimal.ZERO;

    @Column(name = "size_lapsed", nullable = false)
    private BigDecimal sizeLapsed = BigDecimal.ZERO;

    @Column(name = "size_cancelled", nullable = false)
    private BigDecimal sizeCancelled = BigDecimal.ZERO;

    @Column(name = "size_voided", nullable = false)
    private BigDecimal sizeVoided = BigDecimal.ZERO;

    @Column(nullable = false)
    private String status;

    @Column(name = "placed_at")
    private Instant placedAt;

    @Column(name = "matched_at")
    private Instant matchedAt;

    @Column(name = "customer_order_ref")
    private String customerOrderRef;

    @Column(name = "customer_strategy_ref")
    private String customerStrategyRef;

    @Column(name = "last_source", nullable = false)
    private String lastSource;

    @Column(name = "provider_updated_at", nullable = false)
    private Instant providerUpdatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BetOrderEntity() {
    }

    public BetOrderEntity(String betId, UUID intentId, String marketId, long selectionId, BigDecimal handicap,
            BetSide side, Instant now) {
        this.betId = betId;
        this.intentId = intentId;
        this.marketId = marketId;
        this.selectionId = selectionId;
        this.handicap = handicap == null ? BigDecimal.ZERO : handicap;
        this.side = side;
        this.status = EXECUTABLE;
        this.lastSource = "PLACE";
        this.providerUpdatedAt = now;
        this.createdAt = now;
    }

    /** Applies a provider state. Returns true when anything changed. */
    public boolean apply(ProviderOrderState state, Instant now) {
        boolean voidedChanged = state.sizeVoided() != null && state.sizeVoided().compareTo(sizeVoided) != 0;
        BigDecimal newMatched = orZero(state.sizeMatched());
        if (newMatched.compareTo(sizeMatched) < 0 && !voidedChanged) {
            // Stale update (e.g. a delayed poll after a newer stream update): ignore.
            return false;
        }
        String newStatus = state.executionComplete() ? EXECUTION_COMPLETE : EXECUTABLE;
        boolean changed = newMatched.compareTo(sizeMatched) != 0
                || orZero(state.sizeRemaining()).compareTo(sizeRemaining) != 0
                || orZero(state.sizeLapsed()).compareTo(sizeLapsed) != 0
                || orZero(state.sizeCancelled()).compareTo(sizeCancelled) != 0
                || voidedChanged
                || !newStatus.equals(status)
                || !Objects.equals(state.averagePriceMatched(), averagePriceMatched);
        if (state.priceRequested() != null) {
            priceRequested = state.priceRequested();
        }
        if (state.sizeRequested() != null) {
            sizeRequested = state.sizeRequested();
        }
        averagePriceMatched = state.averagePriceMatched();
        sizeMatched = newMatched;
        sizeRemaining = orZero(state.sizeRemaining());
        sizeLapsed = orZero(state.sizeLapsed());
        sizeCancelled = orZero(state.sizeCancelled());
        sizeVoided = orZero(state.sizeVoided());
        status = newStatus;
        if (state.placedAt() != null) {
            placedAt = state.placedAt();
        }
        if (state.matchedAt() != null) {
            matchedAt = state.matchedAt();
        }
        if (state.orderType() != null) {
            orderType = state.orderType();
        }
        if (state.persistenceType() != null) {
            persistenceType = state.persistenceType();
        }
        if (state.customerOrderRef() != null) {
            customerOrderRef = state.customerOrderRef();
        }
        if (state.customerStrategyRef() != null) {
            customerStrategyRef = state.customerStrategyRef();
        }
        lastSource = state.source().name();
        providerUpdatedAt = now;
        return changed;
    }

    /** Marks the order as finished once Betfair reports it cleared (settled/voided/lapsed/cancelled). */
    public void markCleared(Instant now) {
        status = EXECUTION_COMPLETE;
        sizeRemaining = BigDecimal.ZERO;
        lastSource = "CLEARED";
        providerUpdatedAt = now;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public String getBetId() { return betId; }
    public UUID getIntentId() { return intentId; }
    public String getMarketId() { return marketId; }
    public long getSelectionId() { return selectionId; }
    public BigDecimal getHandicap() { return handicap; }
    public BetSide getSide() { return side; }
    public String getOrderType() { return orderType; }
    public String getPersistenceType() { return persistenceType; }
    public BigDecimal getPriceRequested() { return priceRequested; }
    public BigDecimal getSizeRequested() { return sizeRequested; }
    public BigDecimal getAveragePriceMatched() { return averagePriceMatched; }
    public BigDecimal getSizeMatched() { return sizeMatched; }
    public BigDecimal getSizeRemaining() { return sizeRemaining; }
    public BigDecimal getSizeLapsed() { return sizeLapsed; }
    public BigDecimal getSizeCancelled() { return sizeCancelled; }
    public BigDecimal getSizeVoided() { return sizeVoided; }
    public String getStatus() { return status; }
    public Instant getPlacedAt() { return placedAt; }
    public Instant getMatchedAt() { return matchedAt; }
    public String getCustomerOrderRef() { return customerOrderRef; }
    public String getLastSource() { return lastSource; }
    public Instant getProviderUpdatedAt() { return providerUpdatedAt; }
}
