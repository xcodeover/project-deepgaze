package com.deepgaze.history;

/**
 * Runtime-mutable subset of the Time Machine config. Persisted as a single
 * row in {@code deepgaze-app-settings.db} so operators can retune retention
 * from the Settings UI without redeploying; static ops knobs (batchSize,
 * queueCapacity, purgeIntervalMinutes, dbPath) stay in {@code application.yml}.
 *
 * The yml default {@code deepgaze.history.retention-hours} seeds the first
 * load; after that the DB row is authoritative.
 */
public record HistoryConfig(
        int retentionHours,
        long updatedAt
) {
    public static final int MIN_HOURS = 1;
    public static final int MAX_HOURS = 24 * 30; // 30 days — protects the volume from runaway growth

    public static HistoryConfig of(int hours) {
        return new HistoryConfig(clamp(hours), 0L);
    }

    public static int clamp(int hours) {
        if (hours < MIN_HOURS) return MIN_HOURS;
        if (hours > MAX_HOURS) return MAX_HOURS;
        return hours;
    }
}
