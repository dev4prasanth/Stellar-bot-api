package com.stellartrack.realtime;

import com.stellartrack.common.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping(ApiPaths.EVENTS)
@Tag(name = "Real-time updates")
public class EventStreamController {

    private final SseHub hub;
    private final Clock clock;

    public EventStreamController(SseHub hub, Clock clock) {
        this.hub = hub;
        this.clock = clock;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Server-Sent Events of order, recommendation, automation and settlement changes (identifiers only)")
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        Duration lifetime = jwt.getExpiresAt() == null ? Duration.ofMinutes(5)
                : Duration.between(clock.instant(), jwt.getExpiresAt());
        return hub.open(lifetime);
    }
}
