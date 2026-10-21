package com.stellartrack.reporting.application;

import com.stellartrack.reporting.domain.ReportFilter;
import java.sql.Timestamp;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Builds the shared WHERE clause for report queries so every endpoint applies filters identically.
 * Column names are fixed by callers; only values are bound as parameters.
 */
public final class ReportSql {

    private static final Set<String> MODES = Set.of("MANUAL", "AUTOMATIC");
    private static final Set<String> SIDES = Set.of("BACK", "LAY");
    private static final Set<String> TYPES = Set.of("WIN", "PLACE");

    private ReportSql() {
    }

    /**
     * @param alias      table alias exposing configuration_id, mode, side, market_type
     * @param dateColumn qualified date column the range applies to
     */
    public static String where(String alias, String dateColumn, ReportFilter filter, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder(" where ").append(dateColumn).append(" >= :from and ")
                .append(dateColumn).append(" < :to");
        params.addValue("from", Timestamp.from(filter.from())).addValue("to", Timestamp.from(filter.to()));
        if (filter.configurationId() != null) {
            sql.append(" and ").append(alias).append(".configuration_id = :cid");
            params.addValue("cid", filter.configurationId());
        }
        if (filter.mode() != null && MODES.contains(filter.mode())) {
            sql.append(" and ").append(alias).append(".mode = :mode");
            params.addValue("mode", filter.mode());
        }
        if (filter.side() != null && SIDES.contains(filter.side())) {
            sql.append(" and ").append(alias).append(".side = :side");
            params.addValue("side", filter.side());
        }
        if (filter.marketType() != null && TYPES.contains(filter.marketType())) {
            sql.append(" and ").append(alias).append(".market_type = :marketType");
            params.addValue("marketType", filter.marketType());
        }
        return sql.toString();
    }

    public static MapSqlParameterSource params() {
        return new MapSqlParameterSource();
    }
}
