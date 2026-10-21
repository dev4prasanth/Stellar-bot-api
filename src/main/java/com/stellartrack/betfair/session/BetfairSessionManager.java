package com.stellartrack.betfair.session;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditOutcome;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.betfair.config.BetfairProperties;
import com.stellartrack.betfair.error.BetfairApiException;
import com.stellartrack.common.provider.ExchangeAvailability;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Betfair session lifecycle: non-interactive (certificate) login, periodic keep-alive, re-login after
 * INVALID_SESSION_INFORMATION, and logout on shutdown. The session token never leaves this class except as
 * the X-Authentication header, and is never logged.
 */
@Component
public class BetfairSessionManager implements ExchangeAvailability {

    private static final Logger log = LoggerFactory.getLogger(BetfairSessionManager.class);

    private final BetfairProperties properties;
    private final BetfairHttpClients clients;
    private final BetfairConnectionStatus status;
    private final AuditTrail auditTrail;
    private final JsonMapper mapper;
    private final Object loginLock = new Object();
    private volatile String sessionToken;

    public BetfairSessionManager(BetfairProperties properties, BetfairHttpClients clients,
            BetfairConnectionStatus status, AuditTrail auditTrail, JsonMapper mapper) {
        this.properties = properties;
        this.clients = clients;
        this.status = status;
        this.auditTrail = auditTrail;
        this.mapper = mapper;
    }

    @Override
    public Optional<String> unavailableReason() {
        if (!properties.enabled()) {
            return Optional.of("The Betfair connection is turned off in the server settings, so race markets, prices and"
                    + " bets are not available.");
        }
        if (!properties.credentialsConfigured()) {
            return Optional.of("The Betfair connection is not set up yet, so race markets, prices and bets are not"
                    + " available.");
        }
        return Optional.empty();
    }

    /** Betfair settings still to be provided; empty while the connection is turned off or fully configured. */
    public java.util.List<String> missingCredentials() {
        return properties.enabled() ? properties.missingCredentials() : java.util.List.of();
    }

    public String token() {
        unavailableReason().ifPresent(reason -> {
            throw BetfairApiException.disabled(reason);
        });
        String current = sessionToken;
        if (current != null) {
            return current;
        }
        synchronized (loginLock) {
            if (sessionToken == null) {
                sessionToken = login();
            }
            return sessionToken;
        }
    }

    /** Drops the token if it is still the one that failed, so the next call logs in again. */
    public void invalidate(String failedToken) {
        synchronized (loginLock) {
            if (failedToken != null && failedToken.equals(sessionToken)) {
                sessionToken = null;
                status.sessionLost("Session rejected by Betfair");
            }
        }
    }

    public String appKey() {
        return properties.appKey();
    }

    private String login() {
        String form = "username=" + URLEncoder.encode(properties.username(), StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(properties.password(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(properties.certLoginUrl())
                .timeout(properties.readTimeout())
                .header("X-Application", properties.appKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = clients.certificateLogin().send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = mapper.readTree(response.body());
            String loginStatus = body.path("loginStatus").asString("");
            String token = body.path("sessionToken").asString("");
            if (response.statusCode() != 200 || !"SUCCESS".equals(loginStatus) || token.isBlank()) {
                String reason = loginStatus.isBlank() ? "HTTP " + response.statusCode() : loginStatus;
                failLogin(reason);
                throw new BetfairApiException(BetfairApiException.Kind.ACCESS, reason, "Betfair login failed: " + reason, null);
            }
            status.loggedIn();
            auditTrail.recordIndependently(AuditRecord.system(AuditAction.BETFAIR_SESSION_OPENED));
            log.info("Betfair session opened");
            return token;
        } catch (HttpTimeoutException e) {
            failLogin("TIMEOUT");
            throw new BetfairApiException(BetfairApiException.Kind.TIMEOUT, null, "Betfair login timed out", e);
        } catch (IOException e) {
            failLogin("NETWORK");
            throw new BetfairApiException(BetfairApiException.Kind.NETWORK, null, "Betfair login network failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BetfairApiException(BetfairApiException.Kind.NETWORK, null, "Betfair login interrupted", e);
        }
    }

    private void failLogin(String reason) {
        status.sessionLost("Login failed: " + reason);
        auditTrail.recordIndependently(AuditRecord.system(AuditAction.BETFAIR_SESSION_FAILED)
                .outcome(AuditOutcome.FAILURE).details(Map.of("reason", reason)));
    }

    @Scheduled(fixedDelayString = "${stellartrack.betfair.keep-alive-interval}",
            initialDelayString = "${stellartrack.betfair.keep-alive-interval}")
    public void keepAlive() {
        String current = sessionToken;
        if (current == null) {
            return;
        }
        try {
            JsonNode body = identityCall("/keepAlive", current);
            if (!"SUCCESS".equals(body.path("status").asString(""))) {
                log.warn("Betfair keep-alive rejected: {}", body.path("error").asString(""));
                invalidate(current);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Betfair keep-alive failed: {}", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void logout() {
        String current = sessionToken;
        if (current == null) {
            return;
        }
        try {
            identityCall("/logout", current);
        } catch (IOException | RuntimeException e) {
            log.debug("Betfair logout failed during shutdown: {}", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sessionToken = null;
        }
    }

    private JsonNode identityCall(String path, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.identityUrl() + path))
                .timeout(properties.readTimeout())
                .header("Accept", "application/json")
                .header("X-Application", properties.appKey())
                .header("X-Authentication", token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = clients.api().send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(response.body());
    }
}
