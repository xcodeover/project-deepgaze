package com.deepgaze.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable payload broadcast to SSE clients and stored in the in-memory ring buffer.
 * `metricGroup` distinguishes logical metric families per target (e.g. "sessions", "sysstat", "topSql").
 * `rows` is a generic shape so each Collector can shape its result without coupling to a fixed schema.
 * `targetName` is the human-readable label for the target (from DbTargetConfig.displayName()); the
 * frontend prefers it for display and falls back to targetId when blank.
 */
public record MetricSnapshot(
        String targetId,
        String targetName,
        DbType type,
        Instant timestamp,
        String metricGroup,
        List<Map<String, Object>> rows
) {}
