package com.stellartrack.watch.persistence;

import com.stellartrack.watch.domain.WatchConfigurationState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A configuration taking part in a Watch session, pinned to the version current when the session started. */
@Entity
@Table(name = "watch_session_configuration")
public class WatchSessionConfigurationEntity {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "configuration_id", nullable = false)
    private UUID configurationId;

    @Column(name = "configuration_version", nullable = false)
    private int configurationVersion;

    @Column(name = "configuration_name", nullable = false)
    private String configurationName;

    @Column(name = "selection_source", nullable = false)
    private String selectionSource;

    @Column(name = "strategy_code")
    private String strategyCode;

    @Column(nullable = false)
    private String side;

    @Column(name = "market_type", nullable = false)
    private String marketType;

    @Column(name = "staking_method", nullable = false)
    private String stakingMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WatchConfigurationState state;

    @Column(name = "state_reason")
    private String stateReason;

    /** Latest transient condition, e.g. "market data is STALE"; cleared when evaluation proceeds normally. */
    private String notice;

    @Column(nullable = false)
    private int decisions;

    @Column(name = "bets_placed", nullable = false)
    private int betsPlaced;

    @Column(name = "last_tick_at")
    private Instant lastTickAt;

    protected WatchSessionConfigurationEntity() {
    }

    public WatchSessionConfigurationEntity(UUID sessionId, UUID configurationId, int configurationVersion,
            String configurationName, String selectionSource, String strategyCode, String side, String marketType,
            String stakingMethod) {
        this.id = UUID.randomUUID();
        this.sessionId = sessionId;
        this.configurationId = configurationId;
        this.configurationVersion = configurationVersion;
        this.configurationName = configurationName;
        this.selectionSource = selectionSource;
        this.strategyCode = strategyCode;
        this.side = side;
        this.marketType = marketType;
        this.stakingMethod = stakingMethod;
        this.state = WatchConfigurationState.RUNNING;
    }

    public void changeState(WatchConfigurationState state, String reason) {
        this.state = state;
        this.stateReason = truncate(reason);
    }

    public void ticked(Instant now, String notice) {
        this.lastTickAt = now;
        this.notice = truncate(notice);
    }

    public void decisionRecorded(boolean betPlaced) {
        decisions++;
        if (betPlaced) {
            betsPlaced++;
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public UUID getConfigurationId() { return configurationId; }
    public int getConfigurationVersion() { return configurationVersion; }
    public String getConfigurationName() { return configurationName; }
    public String getSelectionSource() { return selectionSource; }
    public String getStrategyCode() { return strategyCode; }
    public String getSide() { return side; }
    public String getMarketType() { return marketType; }
    public String getStakingMethod() { return stakingMethod; }
    public WatchConfigurationState getState() { return state; }
    public String getStateReason() { return stateReason; }
    public String getNotice() { return notice; }
    public int getDecisions() { return decisions; }
    public int getBetsPlaced() { return betsPlaced; }
    public Instant getLastTickAt() { return lastTickAt; }
}
