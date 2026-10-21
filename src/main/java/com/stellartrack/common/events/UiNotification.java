package com.stellartrack.common.events;

import java.util.Map;

/**
 * Lightweight application event forwarded to authorized browsers over Server-Sent Events. Carries identifiers
 * and status only — clients re-fetch authoritative data through the API.
 *
 * @param topic e.g. "orders", "recommendations", "automation", "ingestion", "capital"
 */
public record UiNotification(String topic, String entityId, Map<String, Object> data) {

    public static UiNotification of(String topic, Object entityId, Map<String, Object> data) {
        return new UiNotification(topic, entityId == null ? null : entityId.toString(), data == null ? Map.of() : data);
    }
}
