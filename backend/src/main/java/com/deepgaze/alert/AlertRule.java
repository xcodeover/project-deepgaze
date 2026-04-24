package com.deepgaze.alert;

/**
 * Declarative alert rule bound from application.yml.
 *
 * Kept deliberately narrow: one rule = one threshold on one metric within one
 * group. A future expression-language rule can live alongside this record
 * without touching the evaluator's state machine.
 *
 * Modes:
 *   - metric set          → look up `metric` in the snapshot rows and compare
 *                           its numeric value to `threshold`
 *   - metric=null + count → compare snapshot.rows.size() to `threshold`
 */
public record AlertRule(
        String id,
        String target,         // null matches any target
        String group,          // e.g. "status", "blockers"
        String metric,         // null → count rule
        Op op,                 // GT, GTE, LT, LTE, EQ
        double threshold,
        Long forSeconds,       // null → use engine default
        Severity severity,     // INFO, WARNING, CRITICAL
        String description,    // optional human-readable reason, surfaces in notifications
        /**
         * Row filter for direct-column rule shapes. When set, the extractor
         * reads `metric` only from the row whose name/mountpoint equals this
         * value — used to pin a filesystem rule to a specific mount (e.g.
         * "/var/lib/mysql"). When omitted, the extractor reduces multi-row
         * groups with MAX, i.e. "any row breaches".
         */
        String label
) {
    public enum Op { GT, GTE, LT, LTE, EQ }
    public enum Severity { INFO, WARNING, CRITICAL }

    public boolean isCountRule() {
        return metric == null || metric.isBlank();
    }

    public boolean matches(double observed) {
        return switch (op) {
            case GT  -> observed >  threshold;
            case GTE -> observed >= threshold;
            case LT  -> observed <  threshold;
            case LTE -> observed <= threshold;
            case EQ  -> observed == threshold;
        };
    }
}
