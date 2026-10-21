package com.stellartrack.watch.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.common.events.UiNotification;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.market.application.MarketCatalogService;
import com.stellartrack.market.application.MarketResultService;
import com.stellartrack.market.domain.MarketResult;
import com.stellartrack.market.domain.MarketResultCapturedEvent;
import com.stellartrack.watch.domain.CommissionCalculator;
import com.stellartrack.watch.domain.WatchSettlementCalculator;
import com.stellartrack.watch.persistence.WatchBetEntity;
import com.stellartrack.watch.persistence.WatchBetRepository;
import com.stellartrack.watch.persistence.WatchBetSettlementEntity;
import com.stellartrack.watch.persistence.WatchBetSettlementRepository;
import com.stellartrack.watch.persistence.WatchCapitalEntryEntity;
import com.stellartrack.watch.persistence.WatchMarketCommissionEntity;
import com.stellartrack.watch.persistence.WatchMarketCommissionRepository;
import com.stellartrack.watch.persistence.WatchSessionEntity;
import com.stellartrack.watch.persistence.WatchSessionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settles every Watch bet of a market once its official result is captured, with {@link WatchSettlementCalculator}
 * per bet and {@link CommissionCalculator} per session and market, then books the result on the session's virtual
 * capital. Idempotent: only unsettled bets are settled.
 */
@Service
public class WatchSettlementService {

    private static final Logger log = LoggerFactory.getLogger(WatchSettlementService.class);

    private final WatchBetRepository bets;
    private final WatchBetSettlementRepository settlements;
    private final WatchMarketCommissionRepository commissions;
    private final WatchSessionRepository sessions;
    private final WatchCapitalService capital;
    private final MarketResultService marketResults;
    private final MarketCatalogService catalog;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final JsonCodec json;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WatchSettlementService(WatchBetRepository bets, WatchBetSettlementRepository settlements,
            WatchMarketCommissionRepository commissions, WatchSessionRepository sessions, WatchCapitalService capital,
            MarketResultService marketResults, MarketCatalogService catalog, AuditTrail auditTrail,
            ApplicationEventPublisher events, JsonCodec json, PlatformTransactionManager transactionManager, Clock clock) {
        this.bets = bets;
        this.settlements = settlements;
        this.commissions = commissions;
        this.sessions = sessions;
        this.capital = capital;
        this.marketResults = marketResults;
        this.catalog = catalog;
        this.auditTrail = auditTrail;
        this.events = events;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMarketResult(MarketResultCapturedEvent event) {
        settleMarket(event.marketId());
    }

    /** Safety net for results captured while the event could not be handled: settles every market with a result. */
    public void settleCapturedMarkets() {
        List<String> marketIds = bets.findBySettledAtIsNull().stream().map(WatchBetEntity::getMarketId).distinct().toList();
        for (String marketId : marketIds) {
            try {
                tx.executeWithoutResult(status -> settleMarket(marketId));
            } catch (RuntimeException e) {
                log.warn("Watch settlement of market {} failed: {}", marketId, e.getMessage());
            }
        }
    }

    @Transactional
    public int settleMarket(String marketId) {
        Optional<MarketResult> result = marketResults.result(marketId);
        if (result.isEmpty()) {
            return 0;
        }
        Map<UUID, List<WatchBetEntity>> bySession = bets.findByMarketIdAndSettledAtIsNull(marketId).stream()
                .collect(Collectors.groupingBy(WatchBetEntity::getSessionId));
        BigDecimal baseRate = catalog.require(marketId).marketBaseRate();
        int settled = 0;
        for (Map.Entry<UUID, List<WatchBetEntity>> entry : bySession.entrySet()) {
            WatchSessionEntity session = sessions.findById(entry.getKey()).orElseThrow();
            settled += settleSessionMarket(session, marketId, result.get(),
                    baseRate != null ? baseRate : session.getDefaultCommissionRate(), entry.getValue());
        }
        return settled;
    }

    private int settleSessionMarket(WatchSessionEntity session, String marketId, MarketResult result, BigDecimal baseRate,
            List<WatchBetEntity> marketBets) {
        Instant now = clock.instant();
        List<WatchSettlementCalculator.Result> results = new ArrayList<>();
        for (WatchBetEntity bet : marketBets) {
            if (bet.getClosedAt() == null) {
                bet.closeMatching(result.runner(bet.getSelectionId()).map(MarketResult.RunnerResult::isRemoved).orElse(false),
                        "Market closed: unmatched part lapsed", now);
            }
            results.add(WatchSettlementCalculator.settle(new WatchSettlementCalculator.BetToSettle(bet.getSide(),
                    bet.getMarketType(), bet.getSelectionId(), bet.getSizeMatched(), bet.getAveragePriceMatched(),
                    bet.getRequestedSize(), bet.getRequestedPrice(), bet.getMatchedAt(), bet.getPlacedAt(),
                    bet.getMarketType() == MarketType.EACH_WAY ? catalog.require(marketId).eachWayDivisor() : null), result));
        }
        List<BigDecimal> gross = results.stream().map(WatchSettlementCalculator.Result::grossProfit).toList();
        List<BigDecimal> bestGross = results.stream().map(WatchSettlementCalculator.Result::bestCaseGrossProfit).toList();
        BigDecimal net = sum(gross);
        BigDecimal bestNet = sum(bestGross);
        BigDecimal discount = session.getCommissionDiscountRate();
        BigDecimal commission = CommissionCalculator.commission(net, baseRate, discount);
        BigDecimal bestCommission = CommissionCalculator.commission(bestNet, baseRate, discount);
        List<BigDecimal> shares = CommissionCalculator.attribute(commission, gross);
        List<BigDecimal> bestShares = CommissionCalculator.attribute(bestCommission, bestGross);
        commissions.save(new WatchMarketCommissionEntity(session.getId(), marketId, baseRate, discount, net, commission,
                bestNet, bestCommission, now));
        for (int i = 0; i < marketBets.size(); i++) {
            WatchBetEntity bet = marketBets.get(i);
            WatchSettlementCalculator.Result r = results.get(i);
            settlements.save(new WatchBetSettlementEntity(bet.getId(), WatchSettlementCalculator.RULE_VERSION, r.result(),
                    r.runnerStatus(), r.reductionFactor(), r.deadHeatDivisor(), r.settledPrice(), r.grossProfit(),
                    shares.get(i), r.bestCaseGrossProfit(), bestShares.get(i), json.write(r.evidence()), now));
            bet.settled(r.result(), now);
            capital.append(session.getId(), WatchCapitalEntryEntity.Type.BET_SETTLED, r.grossProfit(), bet.getId(), marketId,
                    "Bet " + r.result());
        }
        capital.append(session.getId(), WatchCapitalEntryEntity.Type.COMMISSION, commission.negate(), null, marketId,
                "Commission at " + baseRate + "% less " + discount + "% discount");
        auditTrail.record(AuditRecord.system(AuditAction.WATCH_BET_SETTLED).entity("market", marketId)
                .details(Map.of("sessionId", session.getId().toString(), "bets", marketBets.size(),
                        "netMarketProfit", net, "commission", commission)));
        events.publishEvent(UiNotification.of("watch", session.getId(), Map.of("settledMarket", marketId)));
        return marketBets.size();
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
