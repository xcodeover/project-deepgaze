package com.deepgaze.queue;

import com.deepgaze.model.MetricSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * Read-only port for the historic buffer. The REST controller depends on this
 * narrow surface and never on the concrete ring-buffer implementation, the
 * underlying ArrayDeque, or the lock that protects it.
 */
public interface MetricBufferReader {

    List<MetricSnapshot> snapshot();

    List<MetricSnapshot> since(Instant since);

    int size();

    int capacity();

    long accepted();

    long evicted();
}
