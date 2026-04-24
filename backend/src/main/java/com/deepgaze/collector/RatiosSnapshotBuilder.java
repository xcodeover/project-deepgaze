package com.deepgaze.collector;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.MetricSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the unified {@code ratios} MetricSnapshot from the raw counter rows
 * a collector already queried (status, sysstat, perfCounters). Derived —
 * never issues its own SQL — so adding this tile costs zero additional
 * round-trips and no new grants on the monitored database.
 *
 * Row shape is the same across all three engines so the RatiosTile renders
 * identically regardless of target:
 *
 *   {metric, label, value, unit, quality, note}
 *     quality: good | warn | bad
 *     unit:    pct  | sec  | ratio | count
 *
 * Returns null when no ratio could be derived (e.g. counters not yet warmed
 * up). BentoTile's skeleton state handles that gracefully.
 */
public final class RatiosSnapshotBuilder {

    private RatiosSnapshotBuilder() {}

    /** MariaDB — derives InnoDB Buffer Pool and Key Buffer hit ratios from SHOW GLOBAL STATUS rows. */
    public static MetricSnapshot forMariaDb(DbTargetConfig target, List<Map<String, Object>> statusRows) {
        if (statusRows == null || statusRows.isEmpty()) return null;
        List<Map<String, Object>> out = new ArrayList<>(3);

        Double innoReads    = kv(statusRows, "Innodb_buffer_pool_reads");
        Double innoRequests = kv(statusRows, "Innodb_buffer_pool_read_requests");
        if (innoRequests != null && innoRequests > 0) {
            double hit = 100.0 * (1.0 - safeDiv(innoReads, innoRequests));
            out.add(ratioRow("innodb_buffer_pool_hit_pct", "InnoDB Buffer Pool",
                    hit, "pct",
                    qualityPct(hit, 99.0, 95.0),
                    "1 - reads / read_requests"));
        }

        Double keyReads    = kv(statusRows, "Key_reads");
        Double keyRequests = kv(statusRows, "Key_read_requests");
        if (keyRequests != null && keyRequests > 0) {
            double hit = 100.0 * (1.0 - safeDiv(keyReads, keyRequests));
            out.add(ratioRow("myisam_key_buffer_hit_pct", "MyISAM Key Buffer",
                    hit, "pct",
                    qualityPct(hit, 99.0, 95.0),
                    "1 - Key_reads / Key_read_requests"));
        }

        Double threadsRunning = kv(statusRows, "Threads_running");
        if (threadsRunning != null) {
            out.add(ratioRow("threads_running", "Threads Running",
                    threadsRunning, "count",
                    threadsRunning > 30 ? "warn" : "good",
                    "active worker threads"));
        }

        return out.isEmpty() ? null : snap(target, out);
    }

    /** Oracle — derives Buffer Cache Hit % and Library Cache Parse Hit % from v$sysstat. */
    public static MetricSnapshot forOracle(DbTargetConfig target, List<Map<String, Object>> sysstatRows) {
        if (sysstatRows == null || sysstatRows.isEmpty()) return null;
        List<Map<String, Object>> out = new ArrayList<>(3);

        Double dbBlock    = kv(sysstatRows, "db block gets");
        Double consistent = kv(sysstatRows, "consistent gets");
        Double physical   = kv(sysstatRows, "physical reads");
        if (dbBlock != null && consistent != null && physical != null) {
            double logical = dbBlock + consistent;
            if (logical > 0) {
                double hit = 100.0 * (1.0 - safeDiv(physical, logical));
                out.add(ratioRow("oracle_buffer_cache_hit_pct", "Buffer Cache",
                        hit, "pct",
                        qualityPct(hit, 95.0, 90.0),
                        "1 - physical / (db block + consistent)"));
            }
        }

        Double parseTotal = kv(sysstatRows, "parse count (total)");
        Double parseHard  = kv(sysstatRows, "parse count (hard)");
        if (parseTotal != null && parseTotal > 0 && parseHard != null) {
            double softPct = 100.0 * (1.0 - safeDiv(parseHard, parseTotal));
            out.add(ratioRow("oracle_parse_soft_pct", "Library Cache Parse",
                    softPct, "pct",
                    qualityPct(softPct, 95.0, 90.0),
                    "1 - hard parses / total"));
        }

        Double execs   = kv(sysstatRows, "execute count");
        if (execs != null) {
            out.add(ratioRow("oracle_exec_count", "Executions (lifetime)",
                    execs, "count", "good",
                    "cumulative since instance start"));
        }

        return out.isEmpty() ? null : snap(target, out);
    }

    /** MSSQL — Buffer Cache Hit Ratio + Page Life Expectancy from dm_os_performance_counters. */
    public static MetricSnapshot forMsSql(DbTargetConfig target, List<Map<String, Object>> perfRows) {
        if (perfRows == null || perfRows.isEmpty()) return null;
        List<Map<String, Object>> out = new ArrayList<>(3);

        Double ple = perfCounter(perfRows, "Page life expectancy");
        if (ple != null) {
            out.add(ratioRow("mssql_page_life_expectancy_sec", "Page Life Expectancy",
                    ple, "sec",
                    ple >= 300 ? "good" : ple >= 180 ? "warn" : "bad",
                    "seconds a page stays in buffer"));
        }

        // `Buffer cache hit ratio` is a PERF_LARGE_RAW_FRACTION — its raw value
        // is cumulative hits, which only becomes a percentage once divided by
        // its PERF_LARGE_RAW_BASE sibling (cumulative hits + misses). Without
        // the base we were reporting the numerator verbatim (~16), which looked
        // like a dying cache on a healthy server.
        Double bchrNumerator = perfCounter(perfRows, "Buffer cache hit ratio");
        Double bchrBase      = perfCounter(perfRows, "Buffer cache hit ratio base");
        if (bchrNumerator != null && bchrBase != null && bchrBase > 0) {
            double pct = 100.0 * bchrNumerator / bchrBase;
            out.add(ratioRow("mssql_buffer_cache_hit_pct", "Buffer Cache",
                    pct, "pct",
                    qualityPct(pct, 98.0, 95.0),
                    "ratio / base · healthy >= 98%"));
        }

        Double processesBlocked = perfCounter(perfRows, "Processes blocked");
        if (processesBlocked != null) {
            out.add(ratioRow("mssql_processes_blocked", "Processes Blocked",
                    processesBlocked, "count",
                    processesBlocked > 0 ? "bad" : "good",
                    "head of blocking chain"));
        }

        return out.isEmpty() ? null : snap(target, out);
    }

    private static Map<String, Object> ratioRow(String metric, String label,
                                                 double value, String unit,
                                                 String quality, String note) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("metric", metric);
        r.put("label",  label);
        r.put("value",  round3(value));
        r.put("unit",   unit);
        r.put("quality", quality);
        r.put("note",   note);
        return r;
    }

    private static MetricSnapshot snap(DbTargetConfig target, List<Map<String, Object>> rows) {
        return new MetricSnapshot(
                target.id(), target.displayName(), target.type(), Instant.now(), "ratios", rows);
    }

    private static String qualityPct(double pct, double goodAt, double warnAt) {
        if (pct >= goodAt) return "good";
        if (pct >= warnAt) return "warn";
        return "bad";
    }

    private static double safeDiv(Double a, Double b) {
        if (a == null || b == null || b == 0) return 0;
        return a / b;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Case-insensitive numeric lookup in a {variable_name|name, value} row shape. */
    private static Double kv(List<Map<String, Object>> rows, String name) {
        for (Map<String, Object> r : rows) {
            Object nm = firstNonNull(r, "variable_name", "Variable_name", "name", "NAME");
            if (nm == null) continue;
            if (!nm.toString().equalsIgnoreCase(name)) continue;
            Object val = firstNonNull(r, "value", "Value", "VALUE");
            return parseDouble(val);
        }
        return null;
    }

    /** MSSQL perfCounters use counter_name instead of variable_name; cntr_value instead of value. */
    private static Double perfCounter(List<Map<String, Object>> rows, String counterName) {
        for (Map<String, Object> r : rows) {
            Object nm = firstNonNull(r, "counter_name", "COUNTER_NAME");
            if (nm == null) continue;
            if (!nm.toString().trim().equalsIgnoreCase(counterName)) continue;
            Object val = firstNonNull(r, "cntr_value", "CNTR_VALUE");
            Double d = parseDouble(val);
            if (d != null) return d;
        }
        return null;
    }

    private static Object firstNonNull(Map<String, Object> row, String... keys) {
        for (String k : keys) {
            Object v = row.get(k);
            if (v != null) return v;
        }
        return null;
    }

    private static Double parseDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
