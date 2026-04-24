package com.deepgaze.alert;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Read-side port for current alert state. REST + SSE controllers depend on
 * this narrow surface and never on the engine's internals.
 */
public interface AlertRegistry {

    List<AlertState> all();

    List<AlertState> firing();

    Flux<AlertEvent> events();
}
