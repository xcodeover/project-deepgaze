package com.deepgaze.alert;

import java.time.Instant;

/**
 * Snapshot of one (rule, target) instance's current state. A rule with
 * target=null produces one instance per observed target, so the store
 * keys by (ruleId, targetId) rather than ruleId alone.
 *
 * `since` tracks the timestamp of the last status transition — used both
 * by the engine (PENDING→FIRING timing) and by the UI (duration display).
 */
public record AlertState(
        String ruleId,
        String targetId,
        AlertRule.Severity severity,
        AlertStatus status,
        Instant since,
        Instant lastEvalAt,
        double lastValue,
        String message
) {}
