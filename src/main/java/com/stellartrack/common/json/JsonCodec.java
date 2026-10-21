package com.stellartrack.common.json;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Single place where application objects are converted to and from JSON text, used for JSONB columns
 * and provider payloads. Keeps persistence entities free of serialization concerns.
 */
@Component
public class JsonCodec {

    private final JsonMapper mapper;

    public JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    public <T> T read(String json, TypeReference<T> type) {
        return mapper.readValue(json, type);
    }

    public JsonNode readTree(String json) {
        return mapper.readTree(json);
    }

    public JsonNode readTree(byte[] json) {
        return mapper.readTree(json);
    }

    public <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    public JsonMapper mapper() {
        return mapper;
    }
}
