package com.stellartrack.betfair.stream;

import com.stellartrack.betfair.config.BetfairProperties;
import com.stellartrack.betfair.error.BetfairApiException;
import com.stellartrack.betfair.session.BetfairSessionManager;
import com.stellartrack.market.port.MarketWatchlist;
import com.stellartrack.order.port.ProviderOrderState;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Betfair Exchange Stream API client (TLS socket, CRLF-delimited JSON).
 *
 * <p>Flow: connection → authentication → marketSubscription + orderSubscription → mcm/ocm/status messages.
 * On any failure the client reconnects with exponential back-off and resubscribes with the last
 * {@code initialClk}/{@code clk}, so Betfair replays the missed deltas (RESUB_DELTA) instead of a full image.
 * The socket read timeout is three heartbeat intervals, so a silent connection is detected as stale.
 *
 * <p>Order changes are published as {@link ProviderOrderState} application events; the order module applies
 * them through the same path as polled orders.
 */
@Component
public class ExchangeStreamClient implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ExchangeStreamClient.class);
    private static final int MAX_STREAM_MARKETS = 200;
    private static final int MARKET_SUBSCRIPTION_ID = 2;
    private static final int ORDER_SUBSCRIPTION_ID = 3;

    private final BetfairProperties properties;
    private final BetfairSessionManager session;
    private final MarketStreamCache cache;
    private final StreamConnectionState state;
    private final MarketWatchlist watchlist;
    private final ApplicationEventPublisher events;
    private final JsonMapper mapper;

    private volatile boolean running;
    private volatile Thread worker;
    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private volatile Set<String> subscribedMarkets = Set.of();
    private volatile String marketInitialClk;
    private volatile String marketClk;
    private volatile String orderInitialClk;
    private volatile String orderClk;

    public ExchangeStreamClient(BetfairProperties properties, BetfairSessionManager session, MarketStreamCache cache,
            StreamConnectionState state, MarketWatchlist watchlist, ApplicationEventPublisher events, JsonMapper mapper) {
        this.properties = properties;
        this.session = session;
        this.cache = cache;
        this.state = state;
        this.watchlist = watchlist;
        this.events = events;
        this.mapper = mapper;
    }

    public boolean enabled() {
        return properties.stream() != null && properties.stream().enabled() && session.unavailableReason().isEmpty();
    }

    @Override
    public void start() {
        running = true;
        if (!enabled()) {
            state.phase(StreamConnectionState.Phase.DISABLED, null);
            return;
        }
        worker = Thread.ofVirtual().name("betfair-stream").start(this::runLoop);
    }

    @Override
    public void stop() {
        running = false;
        closeSocket();
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void runLoop() {
        Duration backoff = Duration.ofSeconds(1);
        while (running) {
            try {
                state.phase(StreamConnectionState.Phase.CONNECTING, null);
                connectAndConsume();
                backoff = Duration.ofSeconds(1);
            } catch (BetfairApiException e) {
                state.phase(StreamConnectionState.Phase.FAILED, e.getMessage());
                log.warn("Betfair stream unavailable: {}", e.getMessage());
            } catch (IOException | RuntimeException e) {
                state.phase(StreamConnectionState.Phase.RECONNECTING, e.getClass().getSimpleName());
                log.warn("Betfair stream disconnected: {}", e.getClass().getSimpleName());
            } finally {
                closeSocket();
            }
            if (!running) {
                return;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Duration max = properties.stream().reconnectMaxBackoff();
            backoff = backoff.multipliedBy(2).compareTo(max) > 0 ? max : backoff.multipliedBy(2);
        }
    }

    private void connectAndConsume() throws IOException {
        Socket s = SSLSocketFactory.getDefault().createSocket(properties.stream().host(), properties.stream().port());
        s.setSoTimeout((int) properties.stream().heartbeat().multipliedBy(3).plusSeconds(5).toMillis());
        socket = s;
        BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

        JsonNode connection = mapper.readTree(requireLine(reader));
        state.connected(connection.path("connectionId").asString(null));

        String token = session.token();
        send(Map.of("op", "authentication", "id", 1, "appKey", session.appKey(), "session", token));
        JsonNode authStatus = mapper.readTree(requireLine(reader));
        if (!"SUCCESS".equals(authStatus.path("statusCode").asString())) {
            String errorCode = authStatus.path("errorCode").asString("UNKNOWN");
            if ("NO_SESSION".equals(errorCode) || "INVALID_SESSION_INFORMATION".equals(errorCode)) {
                session.invalidate(token);
            }
            throw new IOException("Stream authentication failed: " + errorCode);
        }
        Set<String> markets = watchlist.marketIdsToStream(MAX_STREAM_MARKETS);
        if (!markets.isEmpty()) {
            subscribeMarkets(markets, true);
        }
        subscribeOrders();
        state.phase(StreamConnectionState.Phase.SUBSCRIBED, null);

        String line;
        while (running && (line = reader.readLine()) != null) {
            handle(mapper.readTree(line));
        }
        throw new IOException("Stream closed by provider");
    }

    private void handle(JsonNode message) {
        String op = message.path("op").asString();
        switch (op) {
            case "mcm" -> {
                state.messageReceived(message.path("status").asInt(0) == 503);
                if (message.path("id").asInt() == MARKET_SUBSCRIPTION_ID) {
                    rememberClocks(message, true);
                    for (JsonNode change : message.path("mc")) {
                        cache.apply(change);
                    }
                }
            }
            case "ocm" -> {
                state.messageReceived(message.path("status").asInt(0) == 503);
                rememberClocks(message, false);
                List<ProviderOrderState> updates = OrderStreamTranslator.translate(message);
                updates.forEach(events::publishEvent);
            }
            case "status" -> {
                state.messageReceived(false);
                if (!"SUCCESS".equals(message.path("statusCode").asString())) {
                    log.warn("Betfair stream status {}: {}", message.path("errorCode").asString(),
                            message.path("errorMessage").asString());
                }
                if (message.path("connectionClosed").asBoolean(false)) {
                    closeSocket();
                }
            }
            default -> state.messageReceived(false);
        }
    }

    private void rememberClocks(JsonNode message, boolean market) {
        if (message.has("initialClk")) {
            if (market) {
                marketInitialClk = message.path("initialClk").asString();
            } else {
                orderInitialClk = message.path("initialClk").asString();
            }
        }
        if (message.has("clk")) {
            if (market) {
                marketClk = message.path("clk").asString();
            } else {
                orderClk = message.path("clk").asString();
            }
        }
    }

    /** Re-evaluates the watch list periodically; a changed market set replaces the subscription with a fresh image. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void refreshSubscription() {
        if (!running || writer == null || state.snapshot().phase() != StreamConnectionState.Phase.SUBSCRIBED) {
            return;
        }
        Set<String> desired = watchlist.marketIdsToStream(MAX_STREAM_MARKETS);
        // An empty marketIds filter means "every market" to Betfair, so an empty watch list is never sent; the
        // previous subscription simply ages out until markets are available again.
        if (!desired.isEmpty() && !desired.equals(subscribedMarkets)) {
            try {
                subscribeMarkets(desired, false);
            } catch (IOException e) {
                log.warn("Betfair stream resubscription failed: {}", e.getClass().getSimpleName());
                closeSocket();
            }
        }
    }

    private void subscribeMarkets(Set<String> marketIds, boolean reconnecting) throws IOException {
        boolean resume = reconnecting && marketIds.equals(subscribedMarkets) && marketClk != null;
        if (!resume) {
            marketInitialClk = null;
            marketClk = null;
            cache.retainOnly(marketIds);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("op", "marketSubscription");
        request.put("id", MARKET_SUBSCRIPTION_ID);
        request.put("marketFilter", Map.of("marketIds", marketIds));
        request.put("marketDataFilter", Map.of(
                "fields", List.of("EX_BEST_OFFERS", "EX_TRADED_VOL", "EX_LTP", "EX_MARKET_DEF"),
                "ladderLevels", 3));
        request.put("heartbeatMs", properties.stream().heartbeat().toMillis());
        request.put("conflateMs", properties.stream().conflate().toMillis());
        if (resume) {
            request.put("initialClk", marketInitialClk);
            request.put("clk", marketClk);
        }
        send(request);
        subscribedMarkets = Set.copyOf(marketIds);
        state.subscribedMarkets(marketIds.size());
    }

    private void subscribeOrders() throws IOException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("op", "orderSubscription");
        request.put("id", ORDER_SUBSCRIPTION_ID);
        request.put("orderFilter", Map.of("includeOverallPosition", false,
                "customerStrategyRefs", List.of(properties.customerStrategyRef())));
        request.put("segmentationEnabled", true);
        request.put("heartbeatMs", properties.stream().heartbeat().toMillis());
        if (orderClk != null) {
            request.put("initialClk", orderInitialClk);
            request.put("clk", orderClk);
        }
        send(request);
    }

    private synchronized void send(Map<String, Object> message) throws IOException {
        BufferedWriter w = writer;
        if (w == null) {
            throw new IOException("Stream not connected");
        }
        w.write(mapper.writeValueAsString(message));
        w.write("\r\n");
        w.flush();
    }

    private static String requireLine(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new IOException("Stream closed during handshake");
        }
        return line;
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        writer = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing a broken socket
            }
        }
    }
}
