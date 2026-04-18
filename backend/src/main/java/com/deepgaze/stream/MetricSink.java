package com.deepgaze.stream;

import com.deepgaze.model.MetricSnapshot;

/**
 * Write-side port for the metric pipeline. The Scheduler depends on this
 * one-method abstraction so it never sees the concrete broadcaster, the
 * downstream buffer, or any future consumer.
 */
public interface MetricSink {

    void emit(MetricSnapshot snapshot);
}
