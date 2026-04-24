package com.deepgaze.api;

import com.deepgaze.alert.AlertEvent;
import com.deepgaze.alert.AlertRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * SSE transport for alert transitions. Mirrors the shape of MetricSseController:
 * named `event: alert` frames + 15s keepalive comments so proxies don't drop
 * the idle connection.
 */
@RestController
@RequestMapping("/api/stream")
public class AlertSseController {

    private final AlertRegistry registry;

    public AlertSseController(AlertRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(path = "/alerts", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AlertEvent>> alerts() {
        Flux<ServerSentEvent<AlertEvent>> data = registry.events().map(this::toEvent);

        Flux<ServerSentEvent<AlertEvent>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(i -> ServerSentEvent.<AlertEvent>builder().comment("keepalive").build());

        return Flux.merge(data, heartbeat);
    }

    private ServerSentEvent<AlertEvent> toEvent(AlertEvent event) {
        return ServerSentEvent.<AlertEvent>builder()
                .id(event.ruleId() + ":" + event.targetId() + ":" + event.timestamp().toEpochMilli())
                .event("alert")
                .data(event)
                .build();
    }
}
