package com.stellartrack.order.application;

import com.stellartrack.common.api.PageResponse;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.order.domain.OrderIntentStatus;
import com.stellartrack.order.domain.Revocability;
import com.stellartrack.order.persistence.BetOrderEntity;
import com.stellartrack.order.persistence.BetOrderRepository;
import com.stellartrack.order.persistence.CancellationRequestEntity;
import com.stellartrack.order.persistence.CancellationRequestRepository;
import com.stellartrack.order.persistence.OrderIntentEntity;
import com.stellartrack.order.persistence.OrderIntentRepository;
import com.stellartrack.order.persistence.SettlementEntity;
import com.stellartrack.order.persistence.SettlementRepository;
import com.stellartrack.order.persistence.SubmissionAttemptEntity;
import com.stellartrack.order.persistence.SubmissionAttemptRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bet History read model: intention, submission, matching and settlement shown as separate stages. */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    /**
     * @param origin AUTOMATIC = bot (configuration pipeline) bets only, MANUAL = Race Market tickets only
     * @param open   true = only running bets (not settled, not failed, still unmatched or matched and awaiting the
     *               race), regardless of the date range
     */
    public record OrderFilter(Instant from, Instant to, String mode, String intentStatus, UUID configurationId,
            String marketId, String settlementClass, String origin, boolean open) {
    }

    /**
     * @param mode   trading mode the bet was placed under (MANUAL = confirmed by the administrator, AUTOMATIC = no approval)
     * @param origin where the order came from (AUTOMATIC = configuration pipeline, MANUAL = administrator ticket)
     * @param revocable           whether an unmatched stake can be revoked now (see {@link Revocability})
     * @param revokeBlockedReason why it cannot be revoked; null when it can
     * @param dutchGroupId        the Dutch bet this bet is one leg of; null for a single bet
     */
    public record OrderRow(UUID intentId, Instant createdAt, String mode, String purpose, UUID configurationId,
            String configurationName, Integer configurationVersion, String marketId, String venue, Instant marketStart,
            String marketType, long selectionId, String runnerName, String side, BigDecimal requestedPrice,
            BigDecimal requestedSize, String intentStatus, String statusReason, String betId, String betStatus,
            BigDecimal sizeMatched, BigDecimal averagePriceMatched, BigDecimal sizeRemaining, BigDecimal sizeLapsed,
            BigDecimal sizeCancelled, BigDecimal sizeVoided, String settlementClass, BigDecimal settledProfit,
            Integer settlementRevision, UUID recommendationId, String origin, boolean revocable,
            String revokeBlockedReason, UUID dutchGroupId) {
    }

    public record OrderDetail(OrderIntentEntity intent, List<SubmissionAttemptEntity> attempts,
            Optional<BetOrderEntity> order, List<CancellationRequestEntity> cancellations,
            List<SettlementEntity> settlements) {
    }

    private static final String SELECT = """
            select oi.id, oi.created_at, oi.trading_mode as mode, oi.mode as origin, oi.purpose, oi.configuration_id, c.name as configuration_name,
                   oi.configuration_version, oi.market_id, m.venue, m.market_start_time, oi.market_type, oi.selection_id,
                   r.runner_name, oi.side, oi.requested_price, oi.requested_size, oi.status as intent_status,
                   oi.status_reason, b.bet_id, b.status as bet_status, b.size_matched, b.average_price_matched,
                   b.size_remaining, b.size_lapsed, b.size_cancelled, b.size_voided, v.outcome_class, v.profit,
                   v.revision, oi.recommendation_id, oi.dutch_group_id
            from order_intent oi
            join market m on m.market_id = oi.market_id
            left join market_runner r on r.market_id = oi.market_id and r.selection_id = oi.selection_id
            left join bot_configuration c on c.id = oi.configuration_id
            left join bet_order b on b.intent_id = oi.id
            left join v_settled_bet v on v.bet_id = b.bet_id and v.bet_status in ('SETTLED', 'VOIDED')
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final OrderIntentRepository intents;
    private final SubmissionAttemptRepository attempts;
    private final BetOrderRepository betOrders;
    private final CancellationRequestRepository cancellations;
    private final SettlementRepository settlements;

    public OrderQueryService(NamedParameterJdbcTemplate jdbc, OrderIntentRepository intents,
            SubmissionAttemptRepository attempts, BetOrderRepository betOrders,
            CancellationRequestRepository cancellations, SettlementRepository settlements) {
        this.jdbc = jdbc;
        this.intents = intents;
        this.attempts = attempts;
        this.betOrders = betOrders;
        this.cancellations = cancellations;
        this.settlements = settlements;
    }

    public PageResponse<OrderRow> search(OrderFilter filter, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where;
        if (filter.open()) {
            where = new StringBuilder(" where v.outcome_class is null and oi.status in (:live)"
                    + " and (b.bet_id is null or b.status = 'EXECUTABLE' or b.size_matched > 0)");
            params.addValue("live", OrderIntentStatus.LIVE.stream().map(Enum::name).toList());
        } else {
            where = new StringBuilder(" where oi.created_at >= :from and oi.created_at < :to");
            params.addValue("from", Timestamp.from(filter.from())).addValue("to", Timestamp.from(filter.to()));
        }
        if (filter.origin() != null) {
            where.append(" and oi.mode = :origin");
            params.addValue("origin", filter.origin());
        }
        if (filter.mode() != null) {
            where.append(" and oi.trading_mode = :mode");
            params.addValue("mode", filter.mode());
        }
        if (filter.intentStatus() != null) {
            where.append(" and oi.status = :status");
            params.addValue("status", filter.intentStatus());
        }
        if (filter.configurationId() != null) {
            where.append(" and oi.configuration_id = :cid");
            params.addValue("cid", filter.configurationId());
        }
        if (filter.marketId() != null) {
            where.append(" and oi.market_id = :marketId");
            params.addValue("marketId", filter.marketId());
        }
        if (filter.settlementClass() != null) {
            where.append(" and v.outcome_class = :sclass");
            params.addValue("sclass", filter.settlementClass());
        }
        Long total = jdbc.queryForObject("select count(*) from (" + SELECT + where + ") t", params, Long.class);
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<OrderRow> rows = jdbc.query(SELECT + where + " order by oi.created_at desc limit :limit offset :offset",
                params, (rs, i) -> row(rs));
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    public OrderDetail detail(UUID intentId) {
        OrderIntentEntity intent = intents.findById(intentId).orElseThrow(() -> new NotFoundException("Order", intentId));
        Optional<BetOrderEntity> order = betOrders.findByIntentId(intentId);
        return new OrderDetail(intent, attempts.findByIntentIdOrderByAttemptNoAsc(intentId), order,
                order.map(o -> cancellations.findByBetIdOrderByRequestedAtDesc(o.getBetId())).orElse(List.of()),
                order.map(o -> settlements.findByBetIdOrderByBetStatusAscRevisionAsc(o.getBetId())).orElse(List.of()));
    }

    private static OrderRow row(ResultSet rs) throws SQLException {
        Revocability revocability = Revocability.ofOrder(rs.getString("intent_status"), rs.getString("bet_status"),
                rs.getBigDecimal("size_matched"), rs.getBigDecimal("size_remaining"), rs.getBigDecimal("size_cancelled"),
                rs.getBigDecimal("size_lapsed"), rs.getBigDecimal("size_voided"), rs.getString("outcome_class"));
        return new OrderRow((UUID) rs.getObject("id"), ts(rs, "created_at"), rs.getString("mode"), rs.getString("purpose"),
                (UUID) rs.getObject("configuration_id"), rs.getString("configuration_name"),
                (Integer) rs.getObject("configuration_version"), rs.getString("market_id"), rs.getString("venue"),
                ts(rs, "market_start_time"), rs.getString("market_type"), rs.getLong("selection_id"),
                rs.getString("runner_name"), rs.getString("side"), rs.getBigDecimal("requested_price"),
                rs.getBigDecimal("requested_size"), rs.getString("intent_status"), rs.getString("status_reason"),
                rs.getString("bet_id"), rs.getString("bet_status"), rs.getBigDecimal("size_matched"),
                rs.getBigDecimal("average_price_matched"), rs.getBigDecimal("size_remaining"),
                rs.getBigDecimal("size_lapsed"), rs.getBigDecimal("size_cancelled"), rs.getBigDecimal("size_voided"),
                rs.getString("outcome_class"), rs.getBigDecimal("profit"), (Integer) rs.getObject("revision"),
                (UUID) rs.getObject("recommendation_id"), rs.getString("origin"), revocability.revocable(),
                revocability.reason(), (UUID) rs.getObject("dutch_group_id"));
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
