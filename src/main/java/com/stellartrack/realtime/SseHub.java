package com.stellartrack.realtime;

import com.stellartrack.common.events.UiNotification;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans application notifications out to connected, authenticated browsers. Each emitter lives no longer than the
 * access token that opened it, so an expired or revoked session stops receiving updates; the client reconnects
 * with a refreshed token.
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();

    public SseEmitter open(Duration lifetime) {
        SseEmitter emitter = new SseEmitter(Math.max(1_000L, lifetime.toMillis()));
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, SseEmitter.event().name("ready").data("{}"));
        return emitter;
    }

    @EventListener
    public void onNotification(UiNotification notification) {
        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().name(notification.topic()).data(notification));
        }
    }

    @Scheduled(fixedDelay = 15_000)
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().comment("heartbeat"));
        }
    }

    public int connections() {
        return emitters.size();
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
            log.debug("Dropped SSE client: {}", e.getClass().getSimpleName());
        }
    }
}
