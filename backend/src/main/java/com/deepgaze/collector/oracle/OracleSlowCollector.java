package com.deepgaze.collector.oracle;

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
 * Oracle storage-saturation collector for the 60s slow queue.
 *
 * All queries read dictionary / dynamic performance views that Oracle keeps
 * memory-resident (SGA) or refreshes via the internal metric server job:
 *
 *   - DBA_TABLESPACE_USAGE_METRICS is precomputed; no segment scan.
 *   - DBA_TABLESPACES / DBA_DATA_FILES / DBA_TEMP_FILES are dictionary views.
 *   - V$RECOVERY_FILE_DEST / V$RECOVERY_AREA_USAGE read SGA structures.
 *
 * None of them take DML locks; consistent-read is used throughout. All run
 * under the slow-queue query timeout (default 10s) and are isolated from the
 * fast loop's worker pool.
 */
@Slf4j
@Component
public class OracleSlowCollector implements SlowCollector {

    private final int queryTimeoutSec;
    private final double warnPct;
    private final double critPct;

    public OracleSlowCollector(DeepGazeProperties props) {
        // Same shape as the fast collector: leave 500ms headroom so the JDBC
        // cancel fires before the surrounding CompletableFuture timeout.
        long slowMs = props.scheduler().slowCollectionTimeoutMs();
        long effective = Math.max(1000L, slowMs - 500L);
        this.queryTimeoutSec = Math.max(1, (int) (effective / 1000L));
        this.warnPct = props.storageSaturation().warnPct();
        this.critPct = props.storageSaturation().critPct();
    }

    @Override
    public DbType supports() { return DbType.ORACLE; }

    /** Permanent / UNDO / TEMP tablespaces. CTE aggregates autoextensible per TS once. */
    private static final String TABLESPACE_SQL = """
            WITH files AS (
              SELECT tablespace_name,
                     MAX(CASE WHEN autoextensible = 'YES' THEN 1 ELSE 0 END) AS ae
                FROM dba_data_files
               GROUP BY tablespace_name
              UNION ALL
              SELECT tablespace_name,
                     MAX(CASE WHEN autoextensible = 'YES' THEN 1 ELSE 0 END) AS ae
                FROM dba_temp_files
               GROUP BY tablespace_name
            )
            SELECT m.tablespace_name                                     AS name,
                   t.contents                                            AS contents,
                   ROUND(m.tablespace_size * t.block_size / 1048576)     AS total_mb,
                   ROUND(m.used_space      * t.block_size / 1048576)     AS used_mb,
                   ROUND(m.used_percent, 2)                              AS used_pct,
                   NVL(MAX(f.ae), 0)                                     AS is_autoext
              FROM dba_tablespace_usage_metrics m
              JOIN dba_tablespaces t ON t.tablespace_name = m.tablespace_name
              LEFT JOIN files f      ON f.tablespace_name = m.tablespace_name
             WHERE t.status = 'ONLINE'
             GROUP BY m.tablespace_name, t.contents, m.tablespace_size, m.used_space,
                      m.used_percent, t.block_size
            """;

    /**
     * FRA / Recovery Area overall fill. The "effective" percent subtracts
     * reclaimable space because Oracle's retention policy will auto-delete
     * those files before archiver hangs — it is what operators watch for
     * ORA-00257.
     */
    private static final String FRA_SQL = """
            SELECT d.name                                                       AS name,
                   ROUND(d.space_limit  / 1048576)                              AS total_mb,
                   ROUND(d.space_used   / 1048576)                              AS used_mb,
                   ROUND(d.space_reclaimable / 1048576)                         AS reclaimable_mb,
                   ROUND((d.space_used - d.space_reclaimable) * 100
                         / NULLIF(d.space_limit, 0), 2)                         AS used_pct_effective,
                   ROUND(d.space_used * 100 / NULLIF(d.space_limit, 0), 2)      AS used_pct_raw
              FROM v$recovery_file_dest d
            """;

    /** FRA per file-type breakdown — answers "who filled the FRA?". */
    private static final String FRA_DETAIL_SQL = """
            SELECT file_type                                                    AS name,
                   percent_space_used                                           AS used_pct,
                   percent_space_reclaimable                                    AS reclaimable_pct,
                   number_of_files                                              AS file_count
              FROM v$recovery_area_usage
             WHERE percent_space_used > 0
            """;

    @Override
    public StorageSaturationDto collectSaturation(DbTargetConfig target, DataSource ds) throws SQLException {
        List<Item> items = new ArrayList<>(32);

        try (Connection c = ds.getConnection()) {
            collectTablespaces(c, items);
            collectFraOverall(c, items);
            collectFraDetail(c, items);
        }

        return new StorageSaturationDto(
                target.id(), target.type(), Instant.now(), List.copyOf(items));
    }

    private void collectTablespaces(Connection c, List<Item> out) {
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(queryTimeoutSec);
            try (ResultSet rs = s.executeQuery(TABLESPACE_SQL)) {
                while (rs.next()) {
                    String name     = rs.getString("name");
                    String contents = rs.getString("contents"); // PERMANENT / UNDO / TEMPORARY
                    Long totalMb    = readLong(rs, "total_mb");
                    Long usedMb     = readLong(rs, "used_mb");
                    Double usedPct  = readDouble(rs, "used_pct");
                    boolean autoExt = rs.getInt("is_autoext") == 1;

                    StorageType type = switch (contents == null ? "" : contents) {
                        case "UNDO"      -> StorageType.UNDO;
                        case "TEMPORARY" -> StorageType.TEMP;
                        default          -> StorageType.TABLESPACE;
                    };

                    Long freeMb = (totalMb != null && usedMb != null) ? Math.max(0L, totalMb - usedMb) : null;
                    out.add(new Item(type, name, totalMb, usedMb, freeMb, usedPct,
                            autoExt, classify(usedPct), null));
                }
            }
        } catch (SQLException e) {
            log.warn("Oracle tablespace saturation failed: state={} code={} msg={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage());
        }
    }

    private void collectFraOverall(Connection c, List<Item> out) {
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(queryTimeoutSec);
            try (ResultSet rs = s.executeQuery(FRA_SQL)) {
                while (rs.next()) {
                    String name       = rs.getString("name");
                    Long totalMb      = readLong(rs, "total_mb");
                    Long usedMb       = readLong(rs, "used_mb");
                    Long reclaimMb    = readLong(rs, "reclaimable_mb");
                    Double effective  = readDouble(rs, "used_pct_effective");
                    Double raw        = readDouble(rs, "used_pct_raw");

                    Long freeMb = (totalMb != null && usedMb != null) ? Math.max(0L, totalMb - usedMb) : null;
                    String note = (reclaimMb != null && reclaimMb > 0)
                            ? String.format("reclaimable %d MB excluded (raw used %s%%)",
                                    reclaimMb, raw == null ? "?" : String.format("%.1f", raw))
                            : null;

                    out.add(new Item(StorageType.FRA,
                            name == null ? "FRA" : name,
                            totalMb, usedMb, freeMb,
                            effective,          // effective % is the operationally meaningful figure
                            null,               // FRA is not a tablespace — autoExtensible doesn't apply
                            classify(effective),
                            note));
                }
            }
        } catch (SQLException e) {
            log.warn("Oracle FRA saturation failed: state={} code={} msg={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage());
        }
    }

    private void collectFraDetail(Connection c, List<Item> out) {
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(queryTimeoutSec);
            try (ResultSet rs = s.executeQuery(FRA_DETAIL_SQL)) {
                while (rs.next()) {
                    String fileType   = rs.getString("name");
                    Double usedPct    = readDouble(rs, "used_pct");
                    Double reclaimPct = readDouble(rs, "reclaimable_pct");
                    int fileCount     = rs.getInt("file_count");

                    // Only the archive-log slice gets its own enum; the rest
                    // ride under DATAFILE so the frontend can still render
                    // them in a generic row.
                    StorageType type = "ARCHIVED LOG".equalsIgnoreCase(fileType)
                            ? StorageType.ARCHIVE_LOG
                            : StorageType.DATAFILE;

                    String note = String.format(
                            "FRA component · %d file(s)%s",
                            fileCount,
                            (reclaimPct != null && reclaimPct > 0)
                                    ? String.format(", %.1f%% reclaimable", reclaimPct)
                                    : "");

                    out.add(new Item(type, fileType,
                            null, null, null, usedPct, null, classify(usedPct), note));
                }
            }
        } catch (SQLException e) {
            log.warn("Oracle FRA detail saturation failed: state={} code={} msg={}",
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
