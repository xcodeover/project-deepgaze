package com.deepgaze.stream;

import com.deepgaze.model.MetricSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;

/**
 * Hot in-memory fan-out broker. Implements both ports — write (MetricSink) and
 * read (MetricStream) — because broker fan-out is a single responsibility with
 * two facets. Consumers depend on the ports, never on this class, so OCP holds.
 *
 * Concurrency: many worker threads emit, so we use Sinks#emitNext with a brief
 * busy-loop failure handler rather than tryEmitNext (which would surface
 * FAIL_NON_SERIALIZED under contention).
 */
@Slf4j
@Component
public class ReactiveMetricBroadcaster implements MetricSink, MetricStream {

    private static final Sinks.EmitFailureHandler RETRY_BRIEFLY =
            Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(10));

    private final Sinks.Many<MetricSnapshot> sink =
            Sinks.many().multicast().directBestEffort();

    @Override
    public void emit(MetricSnapshot snapshot) {
        if (snapshot == null) return;
        try {
            sink.emitNext(snapshot, RETRY_BRIEFLY);
        } catch (Exception e) {
            log.warn("Broadcaster emit failed: target={} group={} err={}",
                    snapshot.targetId(), snapshot.metricGroup(), e.toString());
        }
    }

    @Override
    public Flux<MetricSnapshot> stream() {
        return sink.asFlux();
    }
}
