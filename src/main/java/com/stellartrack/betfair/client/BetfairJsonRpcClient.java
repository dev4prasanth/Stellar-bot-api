package com.stellartrack.betfair.client;

import com.stellartrack.betfair.api.BetfairOperation;
import com.stellartrack.betfair.config.BetfairProperties;
import com.stellartrack.betfair.error.BetfairApiException;
import com.stellartrack.betfair.session.BetfairConnectionStatus;
import com.stellartrack.betfair.session.BetfairHttpClients;
import com.stellartrack.betfair.session.BetfairSessionManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Betfair JSON-RPC transport. Responsibilities: authentication headers, error normalization,
 * one re-login on an invalid session, and bounded exponential back-off retries for idempotent reads.
 * Write operations ({@code placeOrders}, {@code cancelOrders}) are never retried after a transport failure.
 */
@Component
public class BetfairJsonRpcClient {

    private static final Logger log = LoggerFactory.getLogger(BetfairJsonRpcClient.class);

    private final BetfairProperties properties;
    private final BetfairHttpClients clients;
    private final BetfairSessionManager session;
    private final BetfairConnectionStatus status;
    private final JsonMapper mapper;

    public BetfairJsonRpcClient(BetfairProperties properties, BetfairHttpClients clients,
            BetfairSessionManager session, BetfairConnectionStatus status, JsonMapper springMapper) {
        this.properties = properties;
        this.clients = clients;
        this.session = session;
        this.status = status;
        // Provider responses may gain fields at any time; unknown properties must not break parsing.
        this.mapper = springMapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    public <T> T call(BetfairOperation operation, Map<String, Object> params, TypeReference<T> resultType) {
        int maxAttempts = operation.safeToRetry() ? Math.max(1, properties.readRetry().maxAttempts()) : 1;
        boolean reloggedIn = false;
        int attempt = 0;
        while (true) {
            attempt++;
            String token = session.token();
            try {
                T result = invoke(operation, params, resultType, token);
                status.callSucceeded();
                return result;
            } catch (BetfairApiException e) {
                if (e.kind() == BetfairApiException.Kind.SESSION && !reloggedIn) {
                    // The provider rejected the request before executing it, so a single retry is safe for writes too.
                    session.invalidate(token);
                    reloggedIn = true;
                    attempt--;
                    continue;
                }
                if (operation.safeToRetry() && e.retryableRead() && attempt < maxAttempts) {
                    sleep(backoff(attempt, e));
                    continue;
                }
                status.callFailed(operation.name() + ": " + (e.providerErrorCode() != null ? e.providerErrorCode() : e.kind()));
                throw e;
            }
        }
    }

    private <T> T invoke(BetfairOperation operation, Map<String, Object> params, TypeReference<T> resultType,
            String token) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("method", operation.method());
        envelope.put("params", params);
        envelope.put("id", 1);
        URI endpoint = operation.api() == BetfairOperation.Api.BETTING ? properties.bettingUrl() : properties.accountUrl();
        Duration timeout = operation.safeToRetry() ? properties.readTimeout() : properties.orderTimeout();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("X-Application", session.appKey())
                .header("X-Authentication", token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(envelope)))
                .build();
        HttpResponse<String> response;
        try {
            response = clients.api().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new BetfairApiException(BetfairApiException.Kind.TIMEOUT, null,
                    "Betfair " + operation.name() + " timed out", e);
        } catch (IOException e) {
            throw new BetfairApiException(BetfairApiException.Kind.NETWORK, null,
                    "Betfair " + operation.name() + " network failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BetfairApiException(BetfairApiException.Kind.NETWORK, null, "Interrupted", e);
        }
        return parse(operation, response, resultType);
    }

    private <T> T parse(BetfairOperation operation, HttpResponse<String> response, TypeReference<T> resultType) {
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (JacksonException e) {
            BetfairApiException.Kind kind = response.statusCode() >= 500
                    ? BetfairApiException.Kind.TRANSIENT : BetfairApiException.Kind.PROTOCOL;
            throw new BetfairApiException(kind, "HTTP_" + response.statusCode(),
                    "Unreadable Betfair response for " + operation.name(), e);
        }
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String errorCode = findErrorCode(error);
            log.warn("Betfair {} returned error {}", operation.name(), errorCode);
            throw BetfairApiException.fromErrorCode(errorCode, operation.name());
        }
        if (response.statusCode() >= 500) {
            throw new BetfairApiException(BetfairApiException.Kind.TRANSIENT, "HTTP_" + response.statusCode(),
                    "Betfair service error for " + operation.name(), null);
        }
        JsonNode result = root.path("result");
        if (result.isMissingNode()) {
            throw new BetfairApiException(BetfairApiException.Kind.PROTOCOL, null,
                    "Betfair response without result for " + operation.name(), null);
        }
        return mapper.readerFor(resultType).readValue(result);
    }

    /** APINGException / AccountAPINGException details live under error.data.{exceptionName}.errorCode. */
    private static String findErrorCode(JsonNode error) {
        JsonNode data = error.path("data");
        for (JsonNode child : data) {
            if (child.has("errorCode")) {
                return child.path("errorCode").asString();
            }
        }
        String message = error.path("message").asString("");
        return message.isBlank() ? "UNKNOWN" : message;
    }

    private Duration backoff(int attempt, BetfairApiException e) {
        long base = properties.readRetry().initialInterval().toMillis() * (1L << (attempt - 1));
        if ("TOO_MANY_REQUESTS".equals(e.providerErrorCode())) {
            base *= 4;
        }
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, base / 5));
        return Duration.ofMillis(base + jitter);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BetfairApiException(BetfairApiException.Kind.NETWORK, null, "Interrupted during back-off", e);
        }
    }
}
