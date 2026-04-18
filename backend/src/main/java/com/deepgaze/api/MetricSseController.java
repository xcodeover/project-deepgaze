package com.deepgaze.api;

import com.deepgaze.model.MetricSnapshot;
import com.deepgaze.stream.MetricStream;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * SSE transport over the live MetricStream. Depends only on the read-side port
 * (MetricStream) — knows nothing about the broadcaster, the buffer, or the
 * scheduler. Adding a new transport (WebSocket, gRPC, ...) means adding a new
 * controller alongside this one without modifying any existing class (OCP).
 *
 * A 15-second comment heartbeat is merged in to keep the connection alive
 * through proxies that drop idle TCP after 60s.
 */
@RestController
@RequestMapping("/api/stream")
public class MetricSseController {

    private final MetricStream stream;

    public MetricSseController(MetricStream stream) {
        this.stream = stream;
    }

    @GetMapping(path = "/metrics", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<MetricSnapshot>> metrics() {
        Flux<ServerSentEvent<MetricSnapshot>> data = stream.stream().map(this::toEvent);

        Flux<ServerSentEvent<MetricSnapshot>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(i -> ServerSentEvent.<MetricSnapshot>builder()
                        .comment("keepalive")
                        .build());

        return Flux.merge(data, heartbeat);
    }

    private ServerSentEvent<MetricSnapshot> toEvent(MetricSnapshot snapshot) {
        return ServerSentEvent.<MetricSnapshot>builder()
                .id(snapshot.targetId() + ":" + snapshot.timestamp().toEpochMilli())
                .event("metric")
                .data(snapshot)
                .build();
    }
}
