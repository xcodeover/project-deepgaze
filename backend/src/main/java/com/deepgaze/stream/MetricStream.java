package com.deepgaze.stream;

import com.deepgaze.model.MetricSnapshot;
import reactor.core.publisher.Flux;

/**
 * Read-side port for the metric pipeline. Anything that wants live emissions
 * (SSE controller, ring buffer, future WebSocket controller, future persistence
 * sink) depends on this and never on the concrete broadcaster.
 */
public interface MetricStream {

    Flux<MetricSnapshot> stream();
}
