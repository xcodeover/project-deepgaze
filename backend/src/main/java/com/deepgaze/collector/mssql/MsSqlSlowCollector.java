package com.deepgaze.collector.mssql;

import com.deepgaze.collector.slow.SlowCollector;
import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.model.StorageSaturationDto;
import com.deepgaze.model.StorageSaturationDto.Item;
import com.deepgaze.model.StorageSaturationDto.Severity;
import com.deepgaze.model.StorageSaturationDto.StorageType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL Server storage-saturation collector for the 60s slow queue.
 *
 * Three widely-compatible (SQL Server 2012+) DMV queries:
 *   1. sys.master_files aggregated per database — data-file allocated size
 *      and max_size. The "max" is derived from the file's MAXSIZE setting,
 *      with the -1 / 268435456 / 0 sentinels collapsed to "unlimited".
 *   2. sys.dm_os_performance_counters for "Log File(s) Size (KB)" and
 *      "Log File(s) Used Size (KB)" — per-database log fill percentage.
 *      This counter set is exposed on every supported SQL Server back to
 *      2005, so it survives even when sys.dm_db_log_stats is unavailable.
 *   3. sys.dm_os_volume_stats CROSS APPLY — physical drive free space on
 *      each volume holding a data/log file. Volume-level "drive full"
 *      pressure is the operational metric DBAs actually escalate on.
 *
 * All queries read in-memory DMVs, return a handful of rows, and set an
 * explicit statement timeout. tempdb and user DBs are both covered.
 */
@Slf4j
@Component
public class MsSqlSlowCollector implements SlowCollector {

    private final int queryTimeoutSec;
    private final double warnPct;
    private final double critPct;

    public MsSqlSlowCollector(DeepGazeProperties props) {
        long slowMs = props.scheduler().slowCollectionTimeoutMs();
        long effective = Math.max(1000L, slowMs - 500L);
        this.queryTimeoutSec = Math.max(1, (int) (effective / 1000L));
        this.warnPct = props.storageSaturation().warnPct();
        this.critPct = props.storageSaturation().critPct();
    }

    @Override
    public DbType supports() { return DbType.MSSQL; }

    /**
     * Per-DB data-file aggregate. Skips offline / restoring / suspect DBs
     * (state <> 0) because sys.master_files still lists their files but
     * their size is stale. system DBs (master/model/msdb) are kept so the
     * tile is honest about total allocation.
     *
     * Sentinels filtered out of max_size:
     *   -1         unlimited growth
     *    0         fixed at current size (rare — legacy)
     *    268435456 unlimited + 2 TB cap (SQL's internal "no ceiling" marker)
     */
    private static final String DATA_FILES_SQL = """
            SELECT
              CAST(db.name AS NVARCHAR(128))                                            AS db_name,
              CAST(SUM(CASE WHEN mf.type = 0 THEN CAST(mf.size AS BIGINT) END)
                   * 8 / 1024 AS BIGINT)                                                AS size_mb,
              CAST(SUM(CASE WHEN mf.type = 0
                              AND mf.max_size NOT IN (-1, 268435456, 0)
                            THEN CAST(mf.max_size AS BIGINT) END)
                   * 8 / 1024 AS BIGINT)                                                AS max_mb,
              MAX(CASE WHEN mf.type = 0
                         AND (mf.max_size = -1 OR (mf.is_percent_growth = 0 AND mf.growth > 0))
                       THEN 1 ELSE 0 END)                                               AS is_autoext
            FROM sys.master_files mf
            JOIN sys.databases db ON db.database_id = mf.database_id
            WHERE db.state = 0 AND mf.type = 0
            GROUP BY db.name
            """;

    /**
     * Per-DB log space from dm_os_performance_counters. Skips '_Total' and
     * the hidden resource DB. The HAVING guard drops DBs where the counter
     * hasn't populated yet (brand-new DB, counter collector not primed).
     */
    private static final String LOG_SPACE_SQL = """
            SELECT
              RTRIM(instance_name)                                                    AS db_name,
              CAST(MAX(CASE WHEN counter_name LIKE 'Log File(s) Size%'
                            THEN cntr_value END) / 1024 AS BIGINT)                    AS total_log_mb,
              CAST(MAX(CASE WHEN counter_name LIKE 'Log File(s) Used Size%'
                            THEN cntr_value END) / 1024 AS BIGINT)                    AS used_log_mb
            FROM sys.dm_os_performance_counters
            WHERE counter_name IN ('Log File(s) Size (KB)', 'Log File(s) Used Size (KB)')
              AND instance_name NOT IN ('_Total', '', 'mssqlsystemresource')
            GROUP BY RTRIM(instance_name)
            HAVING MAX(CASE WHEN counter_name LIKE 'Log File(s) Size%'
                            THEN cntr_value END) > 0
            """;

    /**
     * Physical volume free space. GROUP BY volume_mount_point collapses the
     * N files that share a drive into one row — otherwise the tile would
     * render the same 80%-full drive once per datafile on it.
     */
    private static final String VOLUME_SQL = """
            SELECT
              v.volume_mount_point                                                      AS mount,
              CAST(MAX(v.total_bytes) / 1048576 AS BIGINT)                              AS total_mb,
              CAST((MAX(v.total_bytes) - MAX(v.available_bytes)) / 1048576 AS BIGINT)   AS used_mb,
              CAST(((CAST(MAX(v.total_bytes) AS DECIMAL(20,2)) - MAX(v.available_bytes))
                    * 100.0 / NULLIF(MAX(v.total_bytes), 0)) AS DECIMAL(5,2))           AS used_pct
            FROM sys.master_files mf
            CROSS APPLY sys.dm_os_volume_stats(mf.database_id, mf.file_id) v
            GROUP BY v.volume_mount_point
            """;

    @Override
    public StorageSaturationDto collectSaturation(DbTargetConfig target, DataSource ds) throws SQLException {
        List<Item> items = new ArrayList<>(32);
        try (Connection c = ds.getConnection()) {
            collectDataFiles(c, items);
            collectLogSpace(c, items);
            collectVolumes(c, items);
        }
        return new StorageSaturationDto(
                target.id(), target.type(), Instant.now(), List.copyOf(items));
    }

    private void collectDataFiles(Connection c, List<Item> out) {
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(queryTimeoutSec);
            try (ResultSet rs = s.executeQuery(DATA_FILES_SQL)) {
                while (rs.next()) {
                    String dbName  = rs.getString("db_name");
                    Long sizeMb    = readLong(rs, "size_mb");
                    Long maxMb     = readLong(rs, "max_mb");
                    boolean autoEx = rs.getInt("is_autoext") == 1;

                    // With a bounded max: emit a classic fill %. Without (unlimited/autogrow):
                    // emit absolute size only — the severity is carried by the volume row.
                    Double usedPct = (sizeMb != null && maxMb != null && maxMb > 0)
                            ? Math.min(100.0, sizeMb * 100.0 / maxMb)
                            : null;
                    Long freeMb = (sizeMb != null && maxMb != null) ? Math.max(0L, maxMb - sizeMb) : null;

                    StorageType type = "tempdb".equalsIgnoreCase(dbName)
                            ? StorageType.TEMPDB
                            : StorageType.DATAFILE;

                    String note = (maxMb == null && autoEx) ? "autogrow, no MAXSIZE set" : null;

                    out.add(new Item(type, dbName == null ? "?" : dbName,
                            maxMb, sizeMb, freeMb, usedPct,
                            autoEx, classify(usedPct), note));
                }
            }
        } catch (SQLException e) {
            log.warn("MSSQL data-file saturation failed: state={} code={} msg={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage());
        }
    }

    private void collectLogSpace(Connection c, List<Item> out) {
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(queryTimeoutSec);
            try (ResultSet rs = s.executeQuery(LOG_SPACE_SQL)) {
                while (rs.next()) {
                    String dbName  = rs.getString("db_name");
                    Long totalLog  = readLong(rs, "total_log_mb");
                    Long usedLog   = readLong(rs, "used_log_mb");

                    Double usedPct = (totalLog != null && totalLog > 0 && usedLog != null)
                            ? Math.min(100.0, usedLog * 100.0 / totalLog)
                            : null;
                    Long freeMb = (totalLog != null && usedLog != null)
                            ? Math.max(0L, totalLog - usedLog)
                            : null;

                    StorageType type = "tempdb".equalsIgnoreCase(dbName)
                            ? StorageType.TEMPDB
                            : StorageType.TX_LOG;

                    out.add(new Item(type,
                            (dbName == null ? "?" : dbName) + " · log",
                            totalLog, usedLog, freeMb, usedPct,
                            null, classify(usedPct), null));
                }
            }
        } catch (SQLException e) {
            log.warn("MSSQL log-space saturation failed: state={} code={} msg={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage());
        }
    }

    private void collectVolumes(Connection c, List<Item> out) {
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(queryTimeoutSec);
            try (ResultSet rs = s.executeQuery(VOLUME_SQL)) {
                while (rs.next()) {
                    String mount   = rs.getString("mount");
                    Long totalMb   = readLong(rs, "total_mb");
                    Long usedMb    = readLong(rs, "used_mb");
                    Double usedPct = readDouble(rs, "used_pct");
                    Long freeMb = (totalMb != null && usedMb != null)
                            ? Math.max(0L, totalMb - usedMb)
                            : null;

                    out.add(new Item(StorageType.DATAFILE,
                            "volume " + (mount == null ? "?" : mount),
                            totalMb, usedMb, freeMb, usedPct,
                            null, classify(usedPct),
                            "physical drive hosting data/log files"));
                }
            }
        } catch (SQLException e) {
            log.warn("MSSQL volume saturation failed: state={} code={} msg={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage());
        }
    }

    private Severity classify(Double usedPct) {
        if (usedPct == null) return Severity.OK;
        double v = usedPct;
        if (v >= critPct) return Severity.CRIT;
        if (v >= warnPct) return Severity.WARN;
        return Severity.OK;
    }

    private static Long readLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Double readDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
