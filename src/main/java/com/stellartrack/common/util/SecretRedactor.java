package com.stellartrack.common.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Removes secret-looking values before data is written to logs or audit details.
 * Matching is by key name, so callers must still avoid putting secrets into free-text messages.
 */
public final class SecretRedactor {

    public static final String REDACTED = "[REDACTED]";
    private static final Set<String> SENSITIVE_FRAGMENTS = Set.of(
            "password", "secret", "token", "session", "authorization", "cookie", "appkey", "app_key",
            "privatekey", "private_key", "credential", "keystore", "jwt");

    private SecretRedactor() {
    }

    public static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "");
        return SENSITIVE_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> redact(Map<String, ?> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (isSensitiveKey(key)) {
                result.put(key, REDACTED);
            } else if (value instanceof Map<?, ?> nested) {
                result.put(key, redact((Map<String, ?>) nested));
            } else {
                result.put(key, value);
            }
        });
        return result;
    }

    /** Truncates free text to a safe length for storage in error columns. */
    public static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
