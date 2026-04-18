package com.deepgaze.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable payload broadcast to SSE clients and stored in the in-memory ring buffer.
 * `metricGroup` distinguishes logical metric families per target (e.g. "sessions", "sysstat", "topSql").
 * `rows` is a generic shape so each Collector can shape its result without coupling to a fixed schema.
 */
public record MetricSnapshot(
        String targetId,
        DbType type,
        Instant timestamp,
        String metricGroup,
        List<Map<String, Object>> rows
) {}
