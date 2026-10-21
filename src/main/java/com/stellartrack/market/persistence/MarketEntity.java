package com.stellartrack.market.persistence;

import com.stellartrack.common.domain.MarketType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "market")
public class MarketEntity {

    @Id
    @Column(name = "market_id")
    private String marketId;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "event_name")
    private String eventName;

    private String venue;

    @Column(name = "country_code")
    private String countryCode;

    @Column(name = "market_name")
    private String marketName;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", nullable = false)
    private MarketType marketType;

    @Column(name = "market_start_time", nullable = false)
    private Instant marketStartTime;

    @Column(name = "number_of_winners")
    private Integer numberOfWinners;

    @Column(name = "race_type")
    private String raceType;

    private String status;

    @Column(name = "in_play", nullable = false)
    private boolean inPlay;

    @Column(name = "catalogue_synced_at", nullable = false)
    private Instant catalogueSyncedAt;

    @Column(name = "status_synced_at")
    private Instant statusSyncedAt;

    @Column(name = "result_captured_at")
    private Instant resultCapturedAt;

    /** Commission base rate in percent from the market description; null until the catalogue supplies it. */
    @Column(name = "market_base_rate")
    private java.math.BigDecimal marketBaseRate;

    /** EACH_WAY markets: the place part pays at 1/divisor of the win odds; null for other markets. */
    @Column(name = "each_way_divisor")
    private java.math.BigDecimal eachWayDivisor;

    protected MarketEntity() {
    }

    public MarketEntity(String marketId) {
        this.marketId = marketId;
    }

    public void updateCatalogue(String eventId, String eventName, String venue, String countryCode, String marketName,
            MarketType marketType, Instant startTime, String raceType, Instant now) {
        this.eventId = eventId;
        this.eventName = eventName;
        this.venue = venue;
        this.countryCode = countryCode;
        this.marketName = marketName;
        this.marketType = marketType;
        this.marketStartTime = startTime;
        this.raceType = raceType;
        this.catalogueSyncedAt = now;
    }

    public void updateStatus(String status, boolean inPlay, Integer numberOfWinners, Instant now) {
        this.status = status;
        this.inPlay = inPlay;
        if (numberOfWinners != null) {
            this.numberOfWinners = numberOfWinners;
        }
        this.statusSyncedAt = now;
    }

    public void markResultCaptured(Instant now) {
        this.resultCapturedAt = now;
    }

    public String getMarketId() { return marketId; }
    public String getEventId() { return eventId; }
    public String getEventName() { return eventName; }
    public String getVenue() { return venue; }
    public String getCountryCode() { return countryCode; }
    public String getMarketName() { return marketName; }
    public MarketType getMarketType() { return marketType; }
    public Instant getMarketStartTime() { return marketStartTime; }
    public Integer getNumberOfWinners() { return numberOfWinners; }
    public String getRaceType() { return raceType; }
    public String getStatus() { return status; }
    public boolean isInPlay() { return inPlay; }
    public Instant getCatalogueSyncedAt() { return catalogueSyncedAt; }
    public Instant getStatusSyncedAt() { return statusSyncedAt; }
    public Instant getResultCapturedAt() { return resultCapturedAt; }
    public java.math.BigDecimal getMarketBaseRate() { return marketBaseRate; }

    public java.math.BigDecimal getEachWayDivisor() { return eachWayDivisor; }

    public void updateEachWayDivisor(java.math.BigDecimal divisor) {
        this.eachWayDivisor = divisor;
    }

    public void updateMarketBaseRate(java.math.BigDecimal rate) {
        this.marketBaseRate = rate;
    }
}
