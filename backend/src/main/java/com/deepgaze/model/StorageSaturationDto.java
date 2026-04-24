package com.deepgaze.model;

import java.time.Instant;
import java.util.List;

/**
 * Point-in-time snapshot of a target's logical storage saturation — tablespaces,
 * FRA, tempdb, transaction log, etc. Produced by the 60s slow-queue and served
 * by GET /api/storage/saturation as the last-known state per target.
 *
 * Separate from {@link MetricSnapshot} on purpose: storage saturation is a
 * low-frequency, high-cost signal that is pulled by clients on demand rather
 * than pushed through the 1s SSE stream.
 */
public record StorageSaturationDto(
        String targetId,
        DbType engine,
        Instant timestamp,
        List<Item> items
) {
    /**
     * One row per logical storage unit. All size fields are MB for a consistent
     * frontend contract; nullable fields encode "unknown/unsupported on this
     * engine" without collapsing to a misleading zero.
     */
    public record Item(
            StorageType storageType,
            String name,
            Long totalMb,
            Long usedMb,
            Long freeMb,
            Double usedPct,
            Boolean autoExtensible,
            Severity severity,
            String note
    ) {}

    public enum StorageType {
        TABLESPACE,   // Oracle permanent TS, MSSQL user DB data file, MariaDB tablespace
        UNDO,         // Oracle UNDO tablespace
        TEMP,         // Oracle TEMP tablespace
        FRA,          // Oracle Flash Recovery Area (overall)
        ARCHIVE_LOG,  // Oracle archived redo log component inside FRA
        TEMPDB,       // MSSQL tempdb
        TX_LOG,       // MSSQL transaction log
        DATAFILE      // Per-datafile fallback (any engine)
    }

    public enum Severity { OK, WARN, CRIT }
}
