package com.deepgaze.alert;

import com.deepgaze.model.MetricSnapshot;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Pulls a numeric observation out of a snapshot. Intentionally tolerant of
 * column-name casing (SHOW GLOBAL STATUS returns Variable_name; our mappers
 * alias to lowercase; both should work) and tolerant of the value being a
 * string that happens to parse as a number (MariaDB returns STATUS values
 * as VARCHAR).
 *
 * Two row shapes are supported. The extractor tries them in order:
 *
 *   1. Key-value rows (SHOW GLOBAL STATUS, sessions, topWaits, businessMetrics):
 *      rows shaped like {variable_name: "THREADS_RUNNING", value: 42} or
 *      {id: "oldest-open-trx-age-sec", value: 128}. The rule's metric is matched
 *      against the name column; value is read from the value column.
 *
 *   2. Direct-column rows (hostCpu, hostMemory, hostDisk): rows shaped like
 *      {user_pct: 12.3, iowait_pct: 2.1, …}. The rule's metric is treated as
 *      the column key. When the group has multiple rows (hostDisk has one
 *      per device), the MAX across rows is returned — i.e. "any disk > 85%"
 *      fires for the hottest disk.
 */
final class MetricExtractor {

    // `id` lets the key-value path match businessMetrics rows, which use the
    // metric id (not variable_name) as the identifier column. `counter_name`
    // and `cntr_value` let the same path match MSSQL's dm_os_performance_counters
    // shape emitted by the perfCounters group.
    private static final List<String> NAME_KEYS  = List.of("variable_name", "name", "mountpoint", "status", "event_name", "id", "counter_name");
    private static final List<String> VALUE_KEYS = List.of("value", "cnt", "count_star", "count", "cntr_value");

    private MetricExtractor() {}

    static OptionalDouble extract(AlertRule rule, MetricSnapshot snap) {
        if (rule.isCountRule()) {
            return OptionalDouble.of(snap.rows() == null ? 0 : snap.rows().size());
        }
        if (snap.rows() == null || snap.rows().isEmpty()) return OptionalDouble.empty();

        OptionalDouble kv = extractKeyValue(rule.metric(), snap);
        if (kv.isPresent()) return kv;

        return extractDirectColumn(rule.metric(), rule.label(), snap);
    }

    private static OptionalDouble extractKeyValue(String target, MetricSnapshot snap) {
        for (Map<String, Object> row : snap.rows()) {
            String name = pickString(row, NAME_KEYS);
            if (name == null || !target.equalsIgnoreCase(name)) continue;
            OptionalDouble v = pickNumber(row, VALUE_KEYS);
            if (v.isPresent()) return v;
        }
        return OptionalDouble.empty();
    }

    /**
     * Direct-column fallback for row shapes that don't carry a variable-name
     * column — the host collectors emit rows like `{user_pct: ..., iowait_pct: ...}`.
     *
     * With label=null, reduces across rows with MAX so a one-per-device group
     * (hostDisk) alerts on the worst offender without needing a device-name label.
     *
     * With label set, reads `column` only from the row whose identifier column
     * (see NAME_KEYS — typically `name` / `mountpoint`) equals label. Lets a
     * rule pin a filesystem to a specific mount, e.g. label=/var/lib/mysql.
     */
    private static OptionalDouble extractDirectColumn(String column, String label, MetricSnapshot snap) {
        if (label != null && !label.isBlank()) {
            for (Map<String, Object> row : snap.rows()) {
                String name = pickString(row, NAME_KEYS);
                if (name == null || !label.equalsIgnoreCase(name)) continue;
                OptionalDouble v = readNumber(caseInsensitiveGet(row, column));
                if (v.isPresent()) return v;
            }
            return OptionalDouble.empty();
        }

        double max = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (Map<String, Object> row : snap.rows()) {
            OptionalDouble v = readNumber(caseInsensitiveGet(row, column));
            if (v.isPresent()) {
                any = true;
                if (v.getAsDouble() > max) max = v.getAsDouble();
            }
        }
        return any ? OptionalDouble.of(max) : OptionalDouble.empty();
    }

    private static OptionalDouble readNumber(Object v) {
        if (v == null) return OptionalDouble.empty();
        if (v instanceof Number n) return OptionalDouble.of(n.doubleValue());
        try {
            return OptionalDouble.of(Double.parseDouble(v.toString()));
        } catch (NumberFormatException ignored) {
            return OptionalDouble.empty();
        }
    }

    private static String pickString(Map<String, Object> row, List<String> keys) {
        for (String k : keys) {
            Object v = caseInsensitiveGet(row, k);
            if (v != null) return v.toString();
        }
        return null;
    }

    private static OptionalDouble pickNumber(Map<String, Object> row, List<String> keys) {
        for (String k : keys) {
            Object v = caseInsensitiveGet(row, k);
            if (v == null) continue;
            if (v instanceof Number n) return OptionalDouble.of(n.doubleValue());
            try {
                return OptionalDouble.of(Double.parseDouble(v.toString()));
            } catch (NumberFormatException ignored) {
                // column exists but isn't numeric — try the next candidate
            }
        }
        return OptionalDouble.empty();
    }

    private static Object caseInsensitiveGet(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }
}
