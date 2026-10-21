package com.stellartrack.order.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.common.events.UiNotification;
import com.stellartrack.common.util.Hashing;
import com.stellartrack.order.persistence.BetOrderRepository;
import com.stellartrack.order.persistence.MarketSettlementEntity;
import com.stellartrack.order.persistence.MarketSettlementRepository;
import com.stellartrack.order.persistence.SettlementEntity;
import com.stellartrack.order.persistence.SettlementRepository;
import com.stellartrack.order.port.ProviderMarketSettlement;
import com.stellartrack.order.port.ProviderSettlement;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores authoritative settlements idempotently. The same provider record is ignored; a changed record
 * (settlement correction) supersedes the current revision and adds a new one, preserving history.
 */
@Component
public class SettlementRecorder {

    private final SettlementRepository settlements;
    private final MarketSettlementRepository marketSettlements;
    private final BetOrderRepository betOrders;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SettlementRecorder(SettlementRepository settlements, MarketSettlementRepository marketSettlements,
            BetOrderRepository betOrders, AuditTrail auditTrail, ApplicationEventPublisher events, Clock clock) {
        this.settlements = settlements;
        this.marketSettlements = marketSettlements;
        this.betOrders = betOrders;
        this.auditTrail = auditTrail;
        this.events = events;
        this.clock = clock;
    }

    /** @return true when a new revision of the (betId, betStatus) settlement unit was stored */
    @Transactional
    public boolean record(ProviderSettlement s) {
        if (!betOrders.existsById(s.betId())) {
            return false;
        }
        String fingerprint = Hashing.sha256Hex(String.join("|", s.betStatus(), Objects.toString(s.betOutcome()),
                Objects.toString(s.settledAt()), Objects.toString(s.priceMatched()), Objects.toString(s.priceReduced()),
                Objects.toString(s.sizeSettled()), Objects.toString(s.sizeCancelled()), Objects.toString(s.profit())));
        Optional<SettlementEntity> current = settlements.findByBetIdAndBetStatusAndCurrentTrue(s.betId(), s.betStatus());
        if (current.isPresent() && current.get().getFingerprint().equals(fingerprint)) {
            return false;
        }
        Instant now = clock.instant();
        int revision = current.map(c -> c.getRevision() + 1).orElse(1);
        current.ifPresent(c -> {
            c.supersede();
            settlements.saveAndFlush(c);
        });
        settlements.save(new SettlementEntity(s.betId(), revision, s.betStatus(), s.betOutcome(), s.settledAt(),
                s.priceRequested(), s.priceMatched(), s.priceReduced(), s.sizeSettled(), s.sizeCancelled(), s.profit(),
                s.lastMatchedAt(), fingerprint, now));
        if ("SETTLED".equals(s.betStatus()) || "VOIDED".equals(s.betStatus())) {
            betOrders.findById(s.betId()).ifPresent(order -> order.markCleared(now));
        }
        auditTrail.record(AuditRecord.system(revision == 1 ? AuditAction.SETTLEMENT_RECORDED : AuditAction.SETTLEMENT_CORRECTED)
                .entity("bet_order", s.betId())
                .details(Map.of("revision", revision, "betStatus", s.betStatus(),
                        "profit", s.profit() == null ? "" : s.profit().toPlainString())));
        events.publishEvent(UiNotification.of("settlements", s.betId(), Map.of("revision", revision)));
        return true;
    }

    @Transactional
    public boolean recordMarket(ProviderMarketSettlement m) {
        String fingerprint = Hashing.sha256Hex(String.join("|", Objects.toString(m.settledAt()),
                Objects.toString(m.profit()), Objects.toString(m.commission())));
        Optional<MarketSettlementEntity> current = marketSettlements.findByMarketIdAndCurrentTrue(m.marketId());
        if (current.isPresent() && current.get().getFingerprint().equals(fingerprint)) {
            return false;
        }
        int revision = current.map(c -> c.getRevision() + 1).orElse(1);
        current.ifPresent(c -> {
            c.supersede();
            marketSettlements.saveAndFlush(c);
        });
        marketSettlements.save(new MarketSettlementEntity(m.marketId(), revision, m.settledAt(), m.profit(),
                m.commission(), fingerprint, clock.instant()));
        return true;
    }
}
