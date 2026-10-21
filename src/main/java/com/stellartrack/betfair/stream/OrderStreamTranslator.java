package com.stellartrack.betfair.stream;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.order.port.ProviderOrderState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Translates Exchange Stream {@code ocm} order changes ({@code oc → orc → uo}) into provider order states.
 * Field abbreviations follow the Stream API specification: p price, s size, side B/L, status E/EC,
 * pt persistence (L/P/MOC), ot order type, pd/md placed/matched epoch millis, avp, sm, sr, sl, sc, sv, rfo, rfs.
 */
final class OrderStreamTranslator {

    private OrderStreamTranslator() {
    }

    static List<ProviderOrderState> translate(JsonNode ocm) {
        List<ProviderOrderState> states = new ArrayList<>();
        for (JsonNode marketChange : ocm.path("oc")) {
            String marketId = marketChange.path("id").asString();
            for (JsonNode runnerChange : marketChange.path("orc")) {
                long selectionId = runnerChange.path("id").asLong();
                BigDecimal handicap = runnerChange.has("hc") ? runnerChange.path("hc").decimalValue() : BigDecimal.ZERO;
                for (JsonNode uo : runnerChange.path("uo")) {
                    states.add(toState(marketId, selectionId, handicap, uo));
                }
            }
        }
        return states;
    }

    private static ProviderOrderState toState(String marketId, long selectionId, BigDecimal handicap, JsonNode uo) {
        return new ProviderOrderState(
                uo.path("id").asString(),
                marketId,
                selectionId,
                handicap,
                "L".equals(uo.path("side").asString()) ? BetSide.LAY : BetSide.BACK,
                decimal(uo, "p"),
                decimal(uo, "s"),
                decimal(uo, "avp"),
                decimal(uo, "sm"),
                decimal(uo, "sr"),
                decimal(uo, "sl"),
                decimal(uo, "sc"),
                decimal(uo, "sv"),
                "EC".equals(uo.path("status").asString()),
                epoch(uo, "pd"),
                epoch(uo, "md"),
                persistence(uo.path("pt").asString(null)),
                orderType(uo.path("ot").asString(null)),
                uo.path("rfo").asString(null),
                uo.path("rfs").asString(null),
                ProviderOrderState.Source.STREAM);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return node.has(field) && !node.path(field).isNull() ? node.path(field).decimalValue() : null;
    }

    private static Instant epoch(JsonNode node, String field) {
        return node.has(field) ? Instant.ofEpochMilli(node.path(field).asLong()) : null;
    }

    private static String persistence(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "L" -> "LAPSE";
            case "P" -> "PERSIST";
            case "MOC" -> "MARKET_ON_CLOSE";
            default -> code;
        };
    }

    private static String orderType(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "L" -> "LIMIT";
            case "LOC" -> "LIMIT_ON_CLOSE";
            case "MOC" -> "MARKET_ON_CLOSE";
            default -> code;
        };
    }
}
