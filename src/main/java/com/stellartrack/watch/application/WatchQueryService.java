package com.stellartrack.watch.application;

import com.stellartrack.common.api.PageResponse;
import com.stellartrack.common.domain.TradingMode;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.order.control.TradingControlService;
import com.stellartrack.order.domain.Revocability;
import com.stellartrack.reporting.domain.ReportFilter;
import com.stellartrack.watch.application.WatchCapitalService.CapitalView;
import com.stellartrack.watch.application.WatchReportService.Totals;
import com.stellartrack.watch.application.WatchReportService.WatchReportFilter;
import com.stellartrack.watch.domain.WatchBetStatus;
import com.stellartrack.watch.persistence.WatchBetEntity;
import com.stellartrack.watch.persistence.WatchBetFillEntity;
import com.stellartrack.watch.persistence.WatchBetFillRepository;
import com.stellartrack.watch.persistence.WatchBetRepository;
import com.stellartrack.watch.persistence.WatchBetSettlementEntity;
import com.stellartrack.watch.persistence.WatchBetSettlementRepository;
import com.stellartrack.watch.persistence.WatchCapitalEntryEntity;
import com.stellartrack.watch.persistence.WatchDecisionEntity;
import com.stellartrack.watch.persistence.WatchDecisionRepository;
import com.stellartrack.watch.persistence.WatchSessionConfigurationEntity;
import com.stellartrack.watch.persistence.WatchSessionEntity;
import com.stellartrack.watch.persistence.WatchSessionRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Read models for the Watch screens. Everything comes from Watch records; nothing reads live orders or funds. */
@Service
@Transactional(readOnly = true)
public class WatchQueryService {

    public record SessionSummary(UUID id, String name, String status, Instant startedAt, String startedBy,
            Instant stoppedAt, String stopReason, TradingMode previousTradingMode, TradingMode resumedTradingMode,
            String capitalSource, BigDecimal startingCapital, String currencyCode, BigDecimal commissionDiscountRate,
            BigDecimal defaultCommissionRate, String fillModelVersion, int slippageTicks, String settlementRuleVersion,
            Instant lastTickAt, String lastError, String notes, CapitalView capital, Totals totals) {
    }

    public record SessionConfiguration(UUID configurationId, int configurationVersion, String name, String selectionSource,
            String strategyCode, String side, String marketType, String stakingMethod, String state, String stateReason,
            String notice, int decisions, int betsPlaced, Instant lastTickAt) {

        public static SessionConfiguration from(WatchSessionConfigurationEntity c) {
            return new SessionConfiguration(c.getConfigurationId(), c.getConfigurationVersion(), c.getConfigurationName(),
                    c.getSelectionSource(), c.getStrategyCode(), c.getSide(), c.getMarketType(), c.getStakingMethod(),
                    c.getState().name(), c.getStateReason(), c.getNotice(), c.getDecisions(), c.getBetsPlaced(),
                    c.getLastTickAt());
        }
    }

    public record SessionDetail(SessionSummary session, List<SessionConfiguration> configurations) {
    }

    /** What the Watch screen needs on load: the global mode and the active session, if any. */
    public record Status(TradingMode tradingMode, SessionDetail activeSession, SessionSummary lastSession) {
    }

    public record CapitalEntry(int seq, String type, BigDecimal amount, BigDecimal balanceAfter, UUID betId,
            String marketId, String description, Instant createdAt) {

        static CapitalEntry from(WatchCapitalEntryEntity e) {
            return new CapitalEntry(e.getSeq(), e.getEntryType().name(), e.getAmount(), e.getBalanceAfter(), e.getBetId(),
                    e.getMarketId(), e.getDescription(), e.getCreatedAt());
        }
    }

    public record CapitalDetail(CapitalView capital, String capitalSource, Instant startedAt, BigDecimal commissionPaid,
            List<CapitalEntry> ledger) {
    }

    /** A Watch bet as listed on screen (one row of {@code v_watch_bet}). */
    public record BetRow(UUID betId, UUID sessionId, UUID configurationId, int configurationVersion, String configurationName,
            String marketId, String marketName, String venue, Instant marketStartTime, long selectionId, String runnerName,
            String side, String marketType, String persistenceType, BigDecimal requestedPrice, BigDecimal requestedSize,
            BigDecimal limitPrice, int slippageTicks, BigDecimal sizeMatched, BigDecimal averagePriceMatched,
            BigDecimal sizeLapsed, String status, String lapseReason, Long dataDelayMs, Instant placedAt, Instant matchedAt,
            Instant settledAt, BigDecimal settledPrice, BigDecimal reductionFactor, Integer deadHeatDivisor,
            BigDecimal grossProfit, BigDecimal commission, BigDecimal netProfit, BigDecimal bestCaseNetProfit,
            boolean revocable, String revokeBlockedReason) {
    }

    public record Fill(int seq, BigDecimal price, BigDecimal size, String reason, Instant bookAsOf, Instant createdAt) {

        static Fill from(WatchBetFillEntity f) {
            return new Fill(f.getSeq(), f.getPrice(), f.getSize(), f.getReason(), f.getBookAsOf(), f.getCreatedAt());
        }
    }

    public record BetDetail(BetRow bet, JsonNode decisionSnapshot, JsonNode bookAtPlacement, List<Fill> fills,
            JsonNode settlementEvidence, String settlementRuleVersion) {
    }

    public record Decision(UUID id, UUID sessionConfigurationId, String marketId, long selectionId, String side,
            String outcome, String reasons, BigDecimal proposedPrice, BigDecimal proposedStake, Instant createdAt) {

        static Decision from(WatchDecisionEntity d) {
            return new Decision(d.getId(), d.getSessionConfigurationId(), d.getMarketId(), d.getSelectionId(),
                    d.getSide().name(), d.getOutcome().name(), d.getReasons(), d.getProposedPrice(), d.getProposedStake(),
                    d.getCreatedAt());
        }
    }

    /** Bet list filter; null fields are not applied. */
    public record BetFilter(UUID sessionId, WatchBetStatus status, UUID configurationId, String side, String marketType) {
    }

    /** Filter-only statuses: groups of display statuses. */
    private static final Map<WatchBetStatus, String> STATUS_GROUPS = Map.of(
            WatchBetStatus.OPEN, "in ('PENDING', 'PARTIALLY_MATCHED', 'MATCHED')",
            WatchBetStatus.SETTLED, "in ('WON', 'LOST', 'VOID')",
            WatchBetStatus.CLOSED, "not in ('PENDING', 'PARTIALLY_MATCHED', 'MATCHED')");

    private final WatchSessionRepository sessions;
    private final WatchSessionService sessionService;
    private final WatchCapitalService capital;
    private final WatchReportService reports;
    private final WatchDecisionRepository decisions;
    private final WatchBetRepository bets;
    private final WatchBetFillRepository fills;
    private final WatchBetSettlementRepository settlements;
    private final TradingControlService tradingControl;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;
    private final Clock clock;

    public WatchQueryService(WatchSessionRepository sessions, WatchSessionService sessionService,
            WatchCapitalService capital, WatchReportService reports, WatchDecisionRepository decisions,
            WatchBetRepository bets, WatchBetFillRepository fills, WatchBetSettlementRepository settlements, TradingControlService tradingControl,
            NamedParameterJdbcTemplate jdbc, JsonCodec json, Clock clock) {
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.capital = capital;
        this.reports = reports;
        this.decisions = decisions;
        this.bets = bets;
        this.fills = fills;
        this.settlements = settlements;
        this.tradingControl = tradingControl;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public Status status(ZoneId zone) {
        Optional<WatchSessionEntity> active = sessionService.active();
        return new Status(tradingControl.tradingMode(), active.map(s -> detail(s, zone)).orElse(null),
                active.isPresent() ? null : sessions.findFirstByOrderByStartedAtDesc().map(s -> summary(s, zone)).orElse(null));
    }

    public PageResponse<SessionSummary> sessions(Pageable pageable, ZoneId zone) {
        return PageResponse.from(sessions.findAllByOrderByStartedAtDesc(pageable), s -> summary(s, zone));
    }

    public SessionDetail session(UUID sessionId, ZoneId zone) {
        return detail(sessionService.require(sessionId), zone);
    }

    public CapitalDetail capital(UUID sessionId) {
        WatchSessionEntity session = sessionService.require(sessionId);
        List<WatchCapitalEntryEntity> ledger = capital.ledger(sessionId);
        BigDecimal commission = ledger.stream().filter(e -> e.getEntryType() == WatchCapitalEntryEntity.Type.COMMISSION)
                .map(WatchCapitalEntryEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add).negate();
        return new CapitalDetail(capital.view(session), session.getCapitalSource().name(), session.getStartedAt(),
                commission, ledger.stream().map(CapitalEntry::from).toList());
    }

    public PageResponse<BetRow> bets(BetFilter filter, Pageable pageable) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" where 1 = 1");
        if (filter.sessionId() != null) {
            where.append(" and v.session_id = :sessionId");
            p.addValue("sessionId", filter.sessionId());
        }
        if (filter.status() != null && STATUS_GROUPS.containsKey(filter.status())) {
            where.append(" and v.display_status ").append(STATUS_GROUPS.get(filter.status()));
        } else if (filter.status() != null) {
            where.append(" and v.display_status = :status");
            p.addValue("status", filter.status().name());
        }
        if (filter.configurationId() != null) {
            where.append(" and v.configuration_id = :configurationId");
            p.addValue("configurationId", filter.configurationId());
        }
        if (filter.side() != null) {
            where.append(" and v.side = :side");
            p.addValue("side", filter.side());
        }
        if (filter.marketType() != null) {
            where.append(" and v.market_type = :marketType");
            p.addValue("marketType", filter.marketType());
        }
        Long total = jdbc.queryForObject("select count(*) from v_watch_bet v" + where, p, Long.class);
        p.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<BetRow> rows = jdbc.query("select * from v_watch_bet v" + where
                + " order by v.placed_at desc limit :limit offset :offset", p, (rs, i) -> betRow(rs));
        return PageResponse.of(rows, pageable.getPageNumber(), pageable.getPageSize(), total == null ? 0 : total);
    }

    public BetDetail bet(UUID betId) {
        List<BetRow> rows = jdbc.query("select * from v_watch_bet v where v.bet_id = :id",
                new MapSqlParameterSource("id", betId), (rs, i) -> betRow(rs));
        BetRow row = rows.stream().findFirst().orElseThrow(() -> new NotFoundException("Watch bet", betId));
        WatchBetEntity entity = bets.findById(betId).orElseThrow(() -> new NotFoundException("Watch bet", betId));
        WatchDecisionEntity decision = decisions.findById(entity.getDecisionId()).orElseThrow();
        Optional<WatchBetSettlementEntity> settlement = settlements.findByBetIdAndCurrentTrue(betId);
        return new BetDetail(row, json.readTree(decision.getSnapshot()), json.readTree(entity.getBookAtPlacement()),
                fills.findByBetIdOrderBySeqAsc(betId).stream().map(Fill::from).toList(),
                settlement.map(s -> json.readTree(s.getEvidence())).orElse(null),
                settlement.map(WatchBetSettlementEntity::getRuleVersion).orElse(null));
    }

    public PageResponse<Decision> decisions(UUID sessionId, Pageable pageable) {
        return PageResponse.from(decisions.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable), Decision::from);
    }

    private SessionDetail detail(WatchSessionEntity session, ZoneId zone) {
        return new SessionDetail(summary(session, zone), sessionService.configurations(session.getId()).stream()
                .map(SessionConfiguration::from).toList());
    }

    private SessionSummary summary(WatchSessionEntity s, ZoneId zone) {
        ReportFilter period = WatchReportService.sessionPeriod(
                new ReportFilter(s.getStartedAt(), clock.instant(), zone, null, null, null, null),
                s.getStartedAt(), s.getStoppedAt(), clock.instant());
        return new SessionSummary(s.getId(), s.getName(), s.getStatus().name(), s.getStartedAt(), s.getStartedBy(),
                s.getStoppedAt(), s.getStopReason(), s.getPreviousTradingMode(), s.getResumedTradingMode(),
                s.getCapitalSource().name(), s.getStartingCapital(), s.getCurrencyCode(), s.getCommissionDiscountRate(),
                s.getDefaultCommissionRate(), s.getFillModelVersion(), s.getSlippageTicks(), s.getSettlementRuleVersion(),
                s.getLastTickAt(), s.getLastError(), s.getNotes(), capital.view(s),
                reports.totals(new WatchReportFilter(s.getId(), period)));
    }

    private static BetRow betRow(ResultSet rs) throws SQLException {
        Revocability revocability = Revocability.ofWatchBet(rs.getString("display_status"));
        return new BetRow(rs.getObject("bet_id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getObject("configuration_id", UUID.class), rs.getInt("configuration_version"),
                rs.getString("configuration_name"), rs.getString("market_id"), rs.getString("market_name"),
                rs.getString("venue"), instant(rs.getTimestamp("market_start_time")), rs.getLong("selection_id"),
                rs.getString("runner_name"), rs.getString("side"), rs.getString("market_type"),
                rs.getString("persistence_type"), rs.getBigDecimal("requested_price"), rs.getBigDecimal("requested_size"),
                rs.getBigDecimal("limit_price"), rs.getInt("slippage_ticks"), rs.getBigDecimal("size_matched"),
                rs.getBigDecimal("average_price_matched"), rs.getBigDecimal("size_lapsed"), rs.getString("display_status"),
                rs.getString("lapse_reason"), (Long) rs.getObject("data_delay_ms"), instant(rs.getTimestamp("placed_at")),
                instant(rs.getTimestamp("matched_at")), instant(rs.getTimestamp("settled_at")),
                rs.getBigDecimal("settled_price"), rs.getBigDecimal("reduction_factor"),
                (Integer) rs.getObject("dead_heat_divisor"), rs.getBigDecimal("gross_profit"), rs.getBigDecimal("commission"),
                rs.getBigDecimal("net_profit"), rs.getBigDecimal("best_case_net_profit"), revocability.revocable(),
                revocability.reason());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
