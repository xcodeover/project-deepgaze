package com.deepgaze.stream;

import com.deepgaze.model.MetricSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hot in-memory fan-out broker. Implements both ports — write (MetricSink) and
 * read (MetricStream) — because broker fan-out is a single responsibility with
 * two facets. Consumers depend on the ports, never on this class, so OCP holds.
 *
 * Concurrency: many collector worker threads race into {@link #emit}. Reactive
 * Streams Rule 1.3 requires that onNext signals to each Subscriber be strictly
 * serial. {@code busyLooping} alone only handles sink-level contention; with
 * several producer threads we also need producer-side mutual exclusion so the
 * downstream SSE subscriber (Spring MVC {@code ResponseBodyEmitter} backed by
 * {@code DeferredResult}) never sees interleaved emissions. A ReentrantLock
 * guards emitNext, and the busy-loop handler stays in place as a second layer
 * of defence should the sink itself still report a non-serialized state.
 */
@Slf4j
@Component
public class ReactiveMetricBroadcaster implements MetricSink, MetricStream {

    private static final Sinks.EmitFailureHandler RETRY_BRIEFLY =
            Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(10));

    private final Sinks.Many<MetricSnapshot> sink =
            Sinks.many().multicast().directBestEffort();

    private final ReentrantLock emitLock = new ReentrantLock();

    @Override
    public void emit(MetricSnapshot snapshot) {
        if (snapshot == null) return;
        emitLock.lock();
        try {
            sink.emitNext(snapshot, RETRY_BRIEFLY);
        } catch (Exception e) {
            log.warn("Broadcaster emit failed: target={} group={} err={}",
                    snapshot.targetId(), snapshot.metricGroup(), e.toString());
        } finally {
            emitLock.unlock();
        }
    }

    @Override
    public Flux<MetricSnapshot> stream() {
        return sink.asFlux();
    }
}
