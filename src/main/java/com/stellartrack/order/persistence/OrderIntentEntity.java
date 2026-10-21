package com.stellartrack.order.persistence;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.common.domain.OperatingMode;
import com.stellartrack.common.exception.ConflictException;
import com.stellartrack.order.domain.OrderIntentStatus;
import com.stellartrack.order.domain.OrderPurpose;
import com.stellartrack.order.domain.OrderReferences;
import com.stellartrack.order.port.PersistenceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An order intention, persisted with its provider references before Betfair is contacted. */
@Entity
@Table(name = "order_intent")
public class OrderIntentEntity {

    private static final Map<OrderIntentStatus, Set<OrderIntentStatus>> TRANSITIONS = Map.of(
            OrderIntentStatus.PENDING_SUBMISSION, EnumSet.of(OrderIntentStatus.SUBMITTING, OrderIntentStatus.TECHNICAL_FAILURE),
            OrderIntentStatus.SUBMITTING, EnumSet.of(OrderIntentStatus.PLACED, OrderIntentStatus.EXCHANGE_REJECTED,
                    OrderIntentStatus.UNKNOWN_OUTCOME, OrderIntentStatus.TECHNICAL_FAILURE),
            OrderIntentStatus.UNKNOWN_OUTCOME, EnumSet.of(OrderIntentStatus.PLACED,
                    OrderIntentStatus.NOT_FOUND_AFTER_RECONCILIATION),
            OrderIntentStatus.PLACED, EnumSet.noneOf(OrderIntentStatus.class),
            OrderIntentStatus.EXCHANGE_REJECTED, EnumSet.noneOf(OrderIntentStatus.class),
            OrderIntentStatus.TECHNICAL_FAILURE, EnumSet.noneOf(OrderIntentStatus.class),
            OrderIntentStatus.NOT_FOUND_AFTER_RECONCILIATION, EnumSet.of(OrderIntentStatus.PLACED));

    @Id
    private UUID id;

    @Column(name = "recommendation_id")
    private UUID recommendationId;

    @Column(name = "configuration_id")
    private UUID configurationId;

    @Column(name = "configuration_version")
    private Integer configurationVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperatingMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "trading_mode", nullable = false, length = 10)
    private OperatingMode tradingMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderPurpose purpose;

    @Column(name = "linked_intent_id")
    private UUID linkedIntentId;

    /** The Dutch bet this order is one leg of; null for a single bet. */
    @Column(name = "dutch_group_id")
    private UUID dutchGroupId;

    @Column(name = "market_id", nullable = false)
    private String marketId;

    @Column(name = "selection_id", nullable = false)
    private long selectionId;

    @Column(nullable = false)
    private BigDecimal handicap;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BetSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", nullable = false)
    private MarketType marketType;

    @Column(name = "requested_price", nullable = false)
    private BigDecimal requestedPrice;

    @Column(name = "requested_size", nullable = false)
    private BigDecimal requestedSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "persistence_type", nullable = false)
    private PersistenceType persistenceType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String staking;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "customer_ref", nullable = false)
    private String customerRef;

    @Column(name = "customer_order_ref", nullable = false)
    private String customerOrderRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderIntentStatus status;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected OrderIntentEntity() {
    }

    public static OrderIntentEntity create(UUID recommendationId, UUID configurationId, Integer configurationVersion,
            OperatingMode mode, OperatingMode tradingMode, OrderPurpose purpose, UUID linkedIntentId, String marketId, long selectionId,
            BigDecimal handicap, BetSide side, MarketType marketType, BigDecimal price, BigDecimal size,
            PersistenceType persistenceType, String stakingJson, String idempotencyKey, String createdBy, Instant now) {
        OrderIntentEntity e = new OrderIntentEntity();
        e.id = UUID.randomUUID();
        e.recommendationId = recommendationId;
        e.configurationId = configurationId;
        e.configurationVersion = configurationVersion;
        e.mode = mode;
        e.tradingMode = tradingMode;
        e.purpose = purpose;
        e.linkedIntentId = linkedIntentId;
        e.marketId = marketId;
        e.selectionId = selectionId;
        e.handicap = handicap;
        e.side = side;
        e.marketType = marketType;
        e.requestedPrice = price;
        e.requestedSize = size;
        e.persistenceType = persistenceType;
        e.staking = stakingJson;
        e.idempotencyKey = idempotencyKey;
        e.customerRef = OrderReferences.customerRef(e.id);
        e.customerOrderRef = OrderReferences.customerOrderRef(e.id);
        e.status = OrderIntentStatus.PENDING_SUBMISSION;
        e.createdBy = createdBy;
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public void transition(OrderIntentStatus target, String reason, Instant now) {
        if (!TRANSITIONS.get(status).contains(target)) {
            throw new ConflictException("INVALID_ORDER_TRANSITION", "Order intent cannot move from " + status + " to " + target);
        }
        this.status = target;
        this.statusReason = reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getRecommendationId() { return recommendationId; }
    public UUID getConfigurationId() { return configurationId; }
    public Integer getConfigurationVersion() { return configurationVersion; }
    public OperatingMode getMode() { return mode; }
    public OperatingMode getTradingMode() { return tradingMode; }
    public OrderPurpose getPurpose() { return purpose; }
    public UUID getLinkedIntentId() { return linkedIntentId; }
    public UUID getDutchGroupId() { return dutchGroupId; }

    public void assignDutchGroup(UUID groupId) {
        this.dutchGroupId = groupId;
    }
    public String getMarketId() { return marketId; }
    public long getSelectionId() { return selectionId; }
    public BigDecimal getHandicap() { return handicap; }
    public BetSide getSide() { return side; }
    public MarketType getMarketType() { return marketType; }
    public BigDecimal getRequestedPrice() { return requestedPrice; }
    public BigDecimal getRequestedSize() { return requestedSize; }
    public PersistenceType getPersistenceType() { return persistenceType; }
    public String getStaking() { return staking; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCustomerRef() { return customerRef; }
    public String getCustomerOrderRef() { return customerOrderRef; }
    public OrderIntentStatus getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
