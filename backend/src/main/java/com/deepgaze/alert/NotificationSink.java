package com.deepgaze.alert;

/**
 * Delivery port for alert transitions. Implementations are side-effectful and
 * should be resilient — a failing webhook must not block the evaluator or
 * other sinks. The engine invokes every registered sink for each transition
 * and swallows individual failures (logged by the sink itself).
 */
public interface NotificationSink {

    String id();

    void deliver(AlertEvent event);
}
