package com.stellartrack.watch.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditOutcome;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.automation.application.ProposalEngine;
import com.stellartrack.common.domain.OperatingMode;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.common.provider.ExchangeAvailability;
import com.stellartrack.configuration.application.ConfigurationService.ResolvedConfiguration;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSummary;
import com.stellartrack.order.application.PlacementCommand;
import com.stellartrack.order.application.PreTradeContextFactory;
import com.stellartrack.order.domain.OrderPurpose;
import com.stellartrack.order.risk.PreTradeContext;
import com.stellartrack.order.risk.PreTradeValidator;
import com.stellartrack.order.risk.RiskViolation;
import com.stellartrack.order.risk.StandardRiskRules;
import com.stellartrack.watch.domain.PaperFillEngine;
import com.stellartrack.watch.domain.WatchDecisionOutcome;
import com.stellartrack.watch.persistence.WatchBetEntity;
import com.stellartrack.watch.persistence.WatchBetFillEntity;
import com.stellartrack.watch.persistence.WatchBetRepository;
import com.stellartrack.watch.persistence.WatchDecisionEntity;
import com.stellartrack.watch.persistence.WatchDecisionRepository;
import com.stellartrack.watch.persistence.WatchSessionConfigurationEntity;
import com.stellartrack.watch.persistence.WatchSessionConfigurationRepository;
import com.stellartrack.watch.persistence.WatchSessionEntity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one evaluated runner into a Watch decision and, when recommended and every risk rule passes against the
 * session's virtual capital and simulated bets, into a simulated bet matched against the evaluated book. Uses the
 * same pre-trade assembly and rules as live orders; it has no path to the exchange's order API.
 */
@Service
public class WatchExecutionService {

    /** @param stopReason set when a stop rule ends the configuration's participation in the session */
    public record Result(WatchDecisionOutcome outcome, String stopReason) {

        static final Result ALREADY_EVALUATED = new Result(null, null);
    }

    private final WatchDecisionRepository decisions;
    private final WatchSessionConfigurationRepository sessionConfigurations;
    private final WatchBetRepository bets;
    private final WatchFillService fills;
    private final WatchCapitalService capital;
    private final WatchExposureQueries history;
    private final PreTradeContextFactory contexts;
    private final PreTradeValidator validator;
    private final ExchangeAvailability exchange;
    private final AuditTrail auditTrail;
    private final JsonCodec json;
    private final Clock clock;

    public WatchExecutionService(WatchDecisionRepository decisions,
            WatchSessionConfigurationRepository sessionConfigurations, WatchBetRepository bets, WatchFillService fills,
            WatchCapitalService capital, WatchExposureQueries history, PreTradeContextFactory contexts,
            PreTradeValidator validator, ExchangeAvailability exchange, AuditTrail auditTrail, JsonCodec json, Clock clock) {
        this.decisions = decisions;
        this.sessionConfigurations = sessionConfigurations;
        this.bets = bets;
        this.fills = fills;
        this.capital = capital;
        this.history = history;
        this.contexts = contexts;
        this.validator = validator;
        this.exchange = exchange;
        this.auditTrail = auditTrail;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public Result execute(WatchSessionEntity session, UUID sessionConfigurationId, ResolvedConfiguration configuration,
            MarketSummary market, MarketPrices book, ProposalEngine.Proposal proposal) {
        WatchSessionConfigurationEntity sessionConfiguration = sessionConfigurations.findById(sessionConfigurationId)
                .orElseThrow(() -> new NotFoundException("Watch session configuration", sessionConfigurationId));
        if (decisions.existsBySessionConfigurationIdAndMarketIdAndSelectionId(sessionConfiguration.getId(),
                market.marketId(), proposal.selectionId())) {
            return Result.ALREADY_EVALUATED;
        }
        WatchDecisionEntity decision = decisions.save(new WatchDecisionEntity(session.getId(), sessionConfiguration.getId(),
                market.marketId(), proposal.selectionId(), proposal.side(), market.marketType(), WatchDecisionOutcome.SKIPPED,
                proposal.recommend() ? null : String.join("; ", proposal.reasons().isEmpty() ? List.of("Not selected") : proposal.reasons()),
                json.write(proposal.snapshot()), proposal.price(), proposal.stake() == null ? null : proposal.stake().stake(),
                proposal.stake() == null ? null : proposal.stake().exposure(), clock.instant()));
        if (!proposal.recommend()) {
            sessionConfiguration.decisionRecorded(false);
            return new Result(WatchDecisionOutcome.SKIPPED, null);
        }
        PlacementCommand command = new PlacementCommand(OperatingMode.AUTOMATIC, OperatingMode.AUTOMATIC, OrderPurpose.ENTRY,
                null, null, configuration, market.marketId(), proposal.selectionId(), proposal.side(), proposal.price(),
                proposal.stake(), configuration.settings().timing().persistenceType(), "watch-" + decision.getId(), "system",
                null, true, new PlacementCommand.AutomationSession(sessionConfiguration.getId(), session.getStartedAt(),
                        session.getStartingCapital()));
        boolean duplicate = bets.existsBySessionIdAndMarketIdAndSelectionIdAndSideAndSettledAtIsNull(session.getId(),
                market.marketId(), proposal.selectionId(), proposal.side());
        PreTradeContext context = contexts.assemble(command, exchange.isAvailable(), book,
                Optional.of(capital.funds(session)), history, sessionConfiguration.getId(), duplicate, true);
        List<RiskViolation> violations = validator.validate(context);
        if (!violations.isEmpty()) {
            return rejected(session, sessionConfiguration, decision, violations);
        }
        decision.outcome(WatchDecisionOutcome.BET_PLACED, null);
        sessionConfiguration.decisionRecorded(true);
        WatchBetEntity bet = bets.save(newBet(session, sessionConfiguration, decision, configuration, market, book, proposal));
        fills.match(bet, book, WatchBetFillEntity.IMMEDIATE);
        auditTrail.record(AuditRecord.system(AuditAction.WATCH_BET_PLACED).entity("watch_bet", bet.getId())
                .configuration(configuration.id(), configuration.version())
                .details(Map.of("sessionId", session.getId().toString(), "marketId", market.marketId(),
                        "selectionId", proposal.selectionId(), "side", proposal.side().name(),
                        "requested", bet.getRequestedSize() + " @ " + bet.getRequestedPrice(),
                        "matched", bet.getSizeMatched().toPlainString())));
        return new Result(WatchDecisionOutcome.BET_PLACED, null);
    }

    private Result rejected(WatchSessionEntity session, WatchSessionConfigurationEntity sessionConfiguration,
            WatchDecisionEntity decision, List<RiskViolation> violations) {
        decision.outcome(WatchDecisionOutcome.RISK_REJECTED,
                violations.stream().map(RiskViolation::message).collect(Collectors.joining("; ")));
        sessionConfiguration.decisionRecorded(false);
        auditTrail.record(AuditRecord.system(AuditAction.WATCH_BET_RISK_REJECTED).outcome(AuditOutcome.DENIED)
                .entity("watch_decision", decision.getId())
                .details(Map.of("sessionId", session.getId().toString(),
                        "violations", violations.stream().map(RiskViolation::code).toList())));
        String stop = violations.stream().filter(v -> StandardRiskRules.SESSION_STOP_CODES.contains(v.code()))
                .map(RiskViolation::message).findFirst().orElse(null);
        return new Result(WatchDecisionOutcome.RISK_REJECTED, stop);
    }

    private WatchBetEntity newBet(WatchSessionEntity session, WatchSessionConfigurationEntity sessionConfiguration,
            WatchDecisionEntity decision, ResolvedConfiguration configuration, MarketSummary market, MarketPrices book,
            ProposalEngine.Proposal proposal) {
        Instant now = clock.instant();
        Map<String, Object> bookAtPlacement = new LinkedHashMap<>();
        bookAtPlacement.put("asOf", book.asOf());
        bookAtPlacement.put("source", book.source().name());
        bookAtPlacement.put("dataDelayed", book.dataDelayed());
        bookAtPlacement.put("version", book.version());
        bookAtPlacement.put("runner", book.runner(proposal.selectionId()).orElse(null));
        return new WatchBetEntity(session.getId(), sessionConfiguration.getId(), decision.getId(), configuration.id(),
                configuration.version(), market.marketId(), proposal.selectionId(), proposal.side(), market.marketType(),
                configuration.settings().timing().persistenceType().name(), proposal.price(), proposal.stake().stake(),
                PaperFillEngine.limitPrice(proposal.side(), proposal.price(), session.getSlippageTicks()),
                session.getSlippageTicks(), book.asOf() == null ? null : Duration.between(book.asOf(), now).toMillis(),
                json.write(bookAtPlacement), now);
    }
}
