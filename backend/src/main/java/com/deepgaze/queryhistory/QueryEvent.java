package com.deepgaze.queryhistory;

/**
 * One observed digest/statement sample from a target, flattened for SQL search.
 * Latency fields carry the engine-native {@code *_timer_wait} value verbatim
 * (picoseconds on MySQL-family, 100ns on MSSQL, microseconds on Oracle) —
 * formatting is the UI's responsibility since each engine's unit differs.
 */
public record QueryEvent(
        String targetId,
        String targetName,
        String engineType,
        long   tsEpochMs,
        String digest,
        String digestText,
        Long   countStar,
        Long   sumRowsSent,
        Long   sumRowsExamined,
        Double avgTimerWait,
        Double sumTimerWait,
        String sourceGroup  // "topDigests" or "slowQueries"
) {}
