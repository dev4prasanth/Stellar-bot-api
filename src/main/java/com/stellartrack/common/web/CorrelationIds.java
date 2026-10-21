package com.stellartrack.common.web;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** Correlation id stored in the logging MDC for the current thread (HTTP request, listener or worker tick). */
public final class CorrelationIds {

    public static final String MDC_KEY = "correlationId";
    public static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationIds() {
    }

    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value != null ? value : newId();
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Accepts an externally supplied id only if it is short and free of control/log-injection characters. */
    public static String sanitizeOrNew(String candidate) {
        return candidate != null && SAFE.matcher(candidate).matches() ? candidate : newId();
    }

    /** Runs {@code work} with {@code correlationId} bound in the MDC, restoring the previous value afterwards. */
    public static <T> T withCorrelationId(String correlationId, Supplier<T> work) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, correlationId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    public static void withCorrelationId(String correlationId, Runnable work) {
        withCorrelationId(correlationId, () -> {
            work.run();
            return null;
        });
    }
}
