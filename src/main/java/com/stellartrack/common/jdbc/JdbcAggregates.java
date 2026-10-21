package com.stellartrack.common.jdbc;

import java.math.BigDecimal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Single-value aggregate queries that treat SQL NULL as zero. */
public final class JdbcAggregates {

    private JdbcAggregates() {
    }

    public static BigDecimal sum(NamedParameterJdbcTemplate jdbc, String sql, MapSqlParameterSource params) {
        BigDecimal value = jdbc.queryForObject(sql, params, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    public static int count(NamedParameterJdbcTemplate jdbc, String sql, MapSqlParameterSource params) {
        Integer value = jdbc.queryForObject(sql, params, Integer.class);
        return value == null ? 0 : value;
    }
}
