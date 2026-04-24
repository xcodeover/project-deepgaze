package com.deepgaze.collector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal Prometheus text-exposition-format parser — just enough to feed the
 * RemoteHostCollector. Full spec: https://prometheus.io/docs/instrumenting/exposition_formats/
 *
 * Shape: one metric sample per non-comment line,
 *     metric_name[{label="value",...}] value [timestamp]
 *
 * Intentional limitations (we don't need them and they'd double the size):
 *   - Histograms / summaries are read as their individual _bucket / _sum / _count
 *     samples — fine for node_exporter counters, not a general-purpose client.
 *   - Timestamps on the sample line are ignored; we use wall-clock of the scrape.
 *   - "NaN" / "+Inf" / "-Inf" are parsed by Double.parseDouble (which handles them).
 *
 * Allowlist filtering happens during parsing so we never materialise the bulk
 * of node_exporter's ~1000 samples — only the ~10 metric names we actually chart.
 */
final class PrometheusTextParser {

    private PrometheusTextParser() {}

    record Sample(String name, Map<String, String> labels, double value) {}

    static List<Sample> parse(String body, Set<String> allowlist) {
        List<Sample> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;

        int len = body.length();
        int i = 0;
        while (i < len) {
            int lineEnd = body.indexOf('\n', i);
            if (lineEnd < 0) lineEnd = len;
            // Skip empty lines and comments (# HELP / # TYPE / #).
            if (lineEnd > i && body.charAt(i) != '#') {
                Sample s = parseLine(body, i, lineEnd, allowlist);
                if (s != null) out.add(s);
            }
            i = lineEnd + 1;
        }
        return out;
    }

    private static Sample parseLine(String src, int start, int end, Set<String> allowlist) {
        // Metric name runs from `start` until the first '{' (labels) or whitespace (value).
        int p = start;
        while (p < end && !isNameBoundary(src.charAt(p))) p++;
        if (p == start) return null;
        String name = src.substring(start, p);
        if (!allowlist.contains(name)) return null;

        Map<String, String> labels;
        if (p < end && src.charAt(p) == '{') {
            int labelEnd = findLabelBlockEnd(src, p + 1, end);
            if (labelEnd < 0) return null;
            labels = parseLabels(src, p + 1, labelEnd);
            p = labelEnd + 1;
        } else {
            labels = Map.of();
        }

        while (p < end && (src.charAt(p) == ' ' || src.charAt(p) == '\t')) p++;
        if (p >= end) return null;

        int valStart = p;
        while (p < end && src.charAt(p) != ' ' && src.charAt(p) != '\t' && src.charAt(p) != '\r') p++;
        if (p == valStart) return null;

        double value;
        try {
            value = Double.parseDouble(src.substring(valStart, p));
        } catch (NumberFormatException e) {
            return null;
        }
        return new Sample(name, labels, value);
    }

    private static boolean isNameBoundary(char c) {
        return c == '{' || c == ' ' || c == '\t';
    }

    /** Finds the matching '}' for a label block, respecting quoted strings with backslash escapes. */
    private static int findLabelBlockEnd(String src, int from, int end) {
        boolean inQuote = false;
        boolean escaped = false;
        for (int i = from; i < end; i++) {
            char c = src.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inQuote) { escaped = true; continue; }
            if (c == '"') { inQuote = !inQuote; continue; }
            if (c == '}' && !inQuote) return i;
        }
        return -1;
    }

    /** Parses comma-separated `name="value"` pairs inside a label block (without the braces). */
    private static Map<String, String> parseLabels(String src, int from, int end) {
        Map<String, String> labels = new LinkedHashMap<>();
        int i = from;
        while (i < end) {
            while (i < end && (src.charAt(i) == ' ' || src.charAt(i) == ',')) i++;
            if (i >= end) break;

            int nameStart = i;
            while (i < end && src.charAt(i) != '=') i++;
            if (i >= end) break;
            String key = src.substring(nameStart, i).trim();
            i++; // skip '='

            while (i < end && src.charAt(i) == ' ') i++;
            if (i >= end || src.charAt(i) != '"') break;
            i++; // skip opening quote

            StringBuilder val = new StringBuilder();
            boolean escaped = false;
            while (i < end) {
                char c = src.charAt(i);
                if (escaped) {
                    // Prom escapes: \\ \" \n — everything else passes through as-is.
                    if (c == 'n') val.append('\n');
                    else val.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    i++;
                    break;
                } else {
                    val.append(c);
                }
                i++;
            }
            labels.put(key, val.toString());
        }
        return labels;
    }
}
