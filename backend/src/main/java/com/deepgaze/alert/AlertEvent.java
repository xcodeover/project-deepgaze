package com.deepgaze.alert;

import java.time.Instant;

/**
 * Emitted on every meaningful status transition: OK→FIRING (fired),
 * FIRING→OK (resolved). PENDING transitions are intentionally NOT events —
 * they're internal evaluator state, not something operators should see.
 *
 * This is what NotificationSinks receive and what `/api/stream/alerts`
 * broadcasts to the UI.
 */
public record AlertEvent(
        Kind kind,
        String ruleId,
        String targetId,
        AlertRule.Severity severity,
        Instant timestamp,
        double value,
        String message
) {
    public enum Kind { FIRED, RESOLVED }
}
