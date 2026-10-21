package com.stellartrack.reporting.application;

import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.reporting.domain.ReportFilter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Descriptive wins-versus-losses comparison of decision-time factors actually stored in the immutable snapshots
 * (plus execution facts such as matched odds and stake). Missing values are counted as missing, never filled in.
 * The output describes historical patterns only; it does not assert causality or predictive power.
 */
@Service
@Transactional(readOnly = true)
public class FactorAnalysisService {

    private static final int MAX_UNITS = 20_000;
    private static final int BINS = 10;

    public record Observation(String betId, UUID recommendationId, String outcome, BigDecimal value) {
    }

    public record Stats(long count, long missing, BigDecimal min, BigDecimal max, BigDecimal mean, BigDecimal median) {
    }

    public record Bin(BigDecimal from, BigDecimal to, long wins, long losses) {
    }

    public record FactorComparison(String factor, String source, Stats wins, Stats losses, List<Bin> histogram) {
    }

    public record FactorAnalysis(long winUnits, long lossUnits, List<String> availableFactors,
            List<FactorComparison> comparisons, List<Observation> observations, String disclaimer) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public FactorAnalysisService(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public FactorAnalysis analyse(ReportFilter filter, String selectedFactor) {
        MapSqlParameterSource p = ReportSql.params();
        String where = ReportSql.where("v", "v.settled_at", filter, p)
                + " and v.outcome_class in ('WON', 'WON_REDUCED', 'LOST')";
        p.addValue("limit", MAX_UNITS);
        Map<String, Map<String, BigDecimal>> valuesByBet = new LinkedHashMap<>();
        Map<String, String> outcomeByBet = new LinkedHashMap<>();
        Map<String, UUID> recommendationByBet = new LinkedHashMap<>();
        Map<String, String> sourceByFactor = new TreeMap<>();
        jdbc.query("""
                select v.bet_id, v.outcome_class, v.recommendation_id, v.price_matched, v.size_settled, v.side,
                       ds.inputs, ds.intermediate, ds.final_score, ds.market_state
                from v_settled_bet v left join decision_snapshot ds on ds.recommendation_id = v.recommendation_id""" + where
                + " order by v.settled_at limit :limit", p, rs -> {
                    String betId = rs.getString("bet_id");
                    Map<String, BigDecimal> values = new LinkedHashMap<>();
                    put(values, sourceByFactor, "matchedOdds", "Betfair settlement", rs.getBigDecimal("price_matched"));
                    put(values, sourceByFactor, "settledStake", "Betfair settlement", rs.getBigDecimal("size_settled"));
                    put(values, sourceByFactor, "finalScore", "Decision snapshot", rs.getBigDecimal("final_score"));
                    readFactors(rs.getString("inputs"), "input:", "Decision snapshot inputs", values, sourceByFactor);
                    readFactors(rs.getString("intermediate"), "intermediate:", "Decision snapshot intermediate", values, sourceByFactor);
                    String marketState = rs.getString("market_state");
                    if (marketState != null) {
                        JsonNode state = json.readTree(marketState);
                        put(values, sourceByFactor, "decisionOdds", "Decision snapshot market state", decimal(state.path("proposedPrice")));
                        put(values, sourceByFactor, "priceRank", "Decision snapshot market state", decimal(state.path("priceRank")));
                        put(values, sourceByFactor, "marketTradedVolume", "Decision snapshot market state",
                                decimal(state.path("marketTotalMatched")));
                    }
                    valuesByBet.put(betId, values);
                    outcomeByBet.put(betId, rs.getString("outcome_class").startsWith("WON") ? "WIN" : "LOSS");
                    recommendationByBet.put(betId, (UUID) rs.getObject("recommendation_id"));
                });
        long wins = outcomeByBet.values().stream().filter("WIN"::equals).count();
        long losses = outcomeByBet.size() - wins;
        List<FactorComparison> comparisons = new ArrayList<>();
        for (Map.Entry<String, String> factor : sourceByFactor.entrySet()) {
            List<BigDecimal> winValues = new ArrayList<>();
            List<BigDecimal> lossValues = new ArrayList<>();
            valuesByBet.forEach((bet, values) -> {
                BigDecimal value = values.get(factor.getKey());
                if (value != null) {
                    ("WIN".equals(outcomeByBet.get(bet)) ? winValues : lossValues).add(value);
                }
            });
            comparisons.add(new FactorComparison(factor.getKey(), factor.getValue(), stats(winValues, wins),
                    stats(lossValues, losses), histogram(winValues, lossValues)));
        }
        List<Observation> observations = selectedFactor == null ? List.of() : valuesByBet.entrySet().stream()
                .filter(e -> e.getValue().containsKey(selectedFactor))
                .map(e -> new Observation(e.getKey(), recommendationByBet.get(e.getKey()), outcomeByBet.get(e.getKey()),
                        e.getValue().get(selectedFactor)))
                .toList();
        return new FactorAnalysis(wins, losses, List.copyOf(sourceByFactor.keySet()), comparisons, observations,
                "Descriptive comparison of historical decision-time values. It does not imply causation or predict future results.");
    }

    private void readFactors(String raw, String prefix, String source, Map<String, BigDecimal> values,
            Map<String, String> sources) {
        if (raw == null) {
            return;
        }
        for (JsonNode factor : json.readTree(raw)) {
            String name = factor.path("name").asString(null);
            BigDecimal value = decimal(factor.path("value"));
            if (name != null) {
                put(values, sources, prefix + name, source, value);
            }
        }
    }

    private static void put(Map<String, BigDecimal> values, Map<String, String> sources, String name, String source,
            BigDecimal value) {
        sources.putIfAbsent(name, source);
        if (value != null) {
            values.put(name, value);
        }
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return node.isNumber() ? node.decimalValue() : new BigDecimal(node.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Stats stats(List<BigDecimal> values, long population) {
        if (values.isEmpty()) {
            return new Stats(0, population, null, null, null, null);
        }
        List<BigDecimal> sorted = values.stream().sorted().toList();
        BigDecimal sum = sorted.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        int n = sorted.size();
        BigDecimal median = n % 2 == 1 ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_EVEN);
        return new Stats(n, population - n, sorted.getFirst(), sorted.getLast(),
                sum.divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_EVEN), median);
    }

    static List<Bin> histogram(List<BigDecimal> wins, List<BigDecimal> losses) {
        List<BigDecimal> all = new ArrayList<>(wins);
        all.addAll(losses);
        if (all.isEmpty()) {
            return List.of();
        }
        BigDecimal min = all.stream().min(Comparator.naturalOrder()).orElseThrow();
        BigDecimal max = all.stream().max(Comparator.naturalOrder()).orElseThrow();
        if (min.compareTo(max) == 0) {
            return List.of(new Bin(min, max, wins.size(), losses.size()));
        }
        BigDecimal width = max.subtract(min).divide(BigDecimal.valueOf(BINS), 6, RoundingMode.HALF_EVEN);
        List<Bin> bins = new ArrayList<>();
        for (int i = 0; i < BINS; i++) {
            BigDecimal from = min.add(width.multiply(BigDecimal.valueOf(i)));
            BigDecimal to = i == BINS - 1 ? max : from.add(width);
            boolean last = i == BINS - 1;
            long w = wins.stream().filter(v -> inBin(v, from, to, last)).count();
            long l = losses.stream().filter(v -> inBin(v, from, to, last)).count();
            bins.add(new Bin(from, to, w, l));
        }
        return bins;
    }

    private static boolean inBin(BigDecimal v, BigDecimal from, BigDecimal to, boolean last) {
        return v.compareTo(from) >= 0 && (last ? v.compareTo(to) <= 0 : v.compareTo(to) < 0);
    }
}
