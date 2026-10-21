package com.stellartrack.messaging.contract;

import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.messaging.domain.ErrorClass;
import com.stellartrack.messaging.domain.InboundMessage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;

/**
 * Tolerant readers for producer payloads: unknown properties are ignored, missing or mistyped values read as
 * {@code null} (the validators then report them), and the date encodings actually on the wire are accepted —
 * Spring AMQP's default Jackson 2 converter writes {@code Instant} as decimal epoch seconds and {@code LocalTime}
 * as an array, other producers write ISO-8601 strings.
 */
public final class PayloadFields {

    /** {@code {venueId}-{yyyy-MM-dd}}, as Layer 1's {@code generateCardId} builds it. */
    public static final Pattern CARD_ID = Pattern.compile("^(\\d+)-(\\d{4}-\\d{2}-\\d{2})$");

    private PayloadFields() {
    }

    /** Parses the body as a JSON object, or fails with {@code MALFORMED_PAYLOAD}. */
    public static JsonNode readObject(JsonCodec json, InboundMessage message) {
        JsonNode root;
        try {
            root = tree(json, message.body());
        } catch (JacksonException e) {
            throw new ContractViolationException(ErrorClass.MALFORMED_PAYLOAD, "Body is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new ContractViolationException(ErrorClass.MALFORMED_PAYLOAD, "Body is not a JSON object");
        }
        return root;
    }

    /** Whether the body is a JSON object holding every given field; empty when it is not parseable at all. */
    public static Optional<Boolean> hasFields(JsonCodec json, InboundMessage message, String... fields) {
        JsonNode root;
        try {
            root = tree(json, message.body());
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        for (String field : fields) {
            if (!present(root.get(field))) {
                return Optional.of(false);
            }
        }
        return Optional.of(true);
    }

    /** Decimals are read exactly: a double would lose the nanoseconds of epoch-second timestamps. */
    private static JsonNode tree(JsonCodec json, byte[] body) {
        return json.mapper().readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(body);
    }

    public static boolean present(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode();
    }

    public static String text(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (!present(node)) {
            return null;
        }
        return node.isString() || node.isNumber() || node.isBoolean() ? node.asString() : null;
    }

    public static Integer integer(JsonNode parent, String field) {
        return integer(parent == null ? null : parent.get(field));
    }

    public static Integer integer(JsonNode node) {
        if (!present(node)) {
            return null;
        }
        if (node.isIntegralNumber() && node.canConvertToInt()) {
            return node.intValue();
        }
        if (node.isNumber()) {
            BigDecimal value = node.decimalValue();
            return value.stripTrailingZeros().scale() <= 0 ? value.intValue() : null;
        }
        return null;
    }

    public static BigDecimal decimal(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (!present(node)) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isString()) {
            try {
                return new BigDecimal(node.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** First present boolean among the given spellings (e.g. {@code top4} and {@code isTop4}); false when absent. */
    public static boolean flag(JsonNode parent, String... fields) {
        for (String field : fields) {
            JsonNode node = parent == null ? null : parent.get(field);
            if (present(node)) {
                return node.isBoolean() ? node.booleanValue() : "true".equalsIgnoreCase(node.asString());
            }
        }
        return false;
    }

    public static List<JsonNode> array(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (!present(node) || !node.isArray()) {
            return null;
        }
        return new ArrayList<>(node.values());
    }

    public static List<Integer> integers(JsonNode parent, String field) {
        List<JsonNode> items = array(parent, field);
        return items == null ? List.of() : items.stream().map(PayloadFields::integer).toList();
    }

    public static List<String> strings(JsonNode parent, String field) {
        List<JsonNode> items = array(parent, field);
        return items == null ? List.of() : items.stream().filter(PayloadFields::present).map(JsonNode::asString).toList();
    }

    /** A JSON object as an ordered map of plain values (nested objects/arrays included), or an empty map. */
    public static Map<String, Object> object(JsonCodec json, JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (!present(node) || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        node.properties().forEach(entry -> result.put(entry.getKey(), json.convert(entry.getValue(), Object.class)));
        return result;
    }

    /**
     * Accepts decimal epoch seconds ({@code 1728812345.123456789}), integral epoch seconds, or ISO-8601 text
     * with or without an offset (no offset is read as UTC).
     *
     * @throws ContractViolationException when a value is present but is no recognisable instant
     */
    public static Instant instant(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (!present(node)) {
            return null;
        }
        if (node.isNumber()) {
            BigDecimal seconds = node.decimalValue();
            long whole = seconds.longValue();
            int nanos = seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).intValue();
            return Instant.ofEpochSecond(whole, nanos);
        }
        if (node.isString()) {
            String value = node.asString().trim();
            if (value.matches("^\\d+(\\.\\d+)?$")) {
                BigDecimal seconds = new BigDecimal(value);
                long whole = seconds.longValue();
                return Instant.ofEpochSecond(whole, seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).intValue());
            }
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException e) {
                try {
                    return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException ignored) {
                    // fall through
                }
            }
        }
        throw new ContractViolationException(ErrorClass.SCHEMA_VALIDATION, field + " is not a valid timestamp");
    }

    /** Accepts {@code [h, m]}, {@code [h, m, s]}, {@code [h, m, s, nanos]} or ISO text such as {@code 10:30}. */
    public static LocalTime localTime(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (!present(node)) {
            return null;
        }
        try {
            if (node.isArray() && node.size() >= 2) {
                int hour = node.get(0).intValue();
                int minute = node.get(1).intValue();
                int second = node.size() > 2 ? node.get(2).intValue() : 0;
                int nanos = node.size() > 3 ? node.get(3).intValue() : 0;
                return LocalTime.of(hour, minute, second, nanos);
            }
            if (node.isString()) {
                return LocalTime.parse(node.asString().trim());
            }
        } catch (RuntimeException e) {
            // reported below
        }
        throw new ContractViolationException(ErrorClass.SCHEMA_VALIDATION, field + " is not a valid time of day");
    }

    /** The venue id and race date encoded in a card id, or empty when it does not follow the contract format. */
    public static Optional<String[]> splitCardId(String cardId) {
        if (cardId == null) {
            return Optional.empty();
        }
        Matcher matcher = CARD_ID.matcher(cardId);
        return matcher.matches() ? Optional.of(new String[] {matcher.group(1), matcher.group(2)}) : Optional.empty();
    }

    /** Adds a non-null value; normalized payload maps must not contain null values. */
    public static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
