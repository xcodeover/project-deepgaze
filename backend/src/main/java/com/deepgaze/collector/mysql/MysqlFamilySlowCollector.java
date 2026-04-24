package com.deepgaze.collector.mysql;

import com.deepgaze.collector.slow.SlowCollector;
import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.StorageSaturationDto;
import com.deepgaze.model.StorageSaturationDto.Item;
import com.deepgaze.model.StorageSaturationDto.Severity;
import com.deepgaze.model.StorageSaturationDto.StorageType;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared slow-queue collection logic for the MySQL / MariaDB family.
 *
 * There is no equivalent of Oracle's DBA_TABLESPACE_USAGE_METRICS on InnoDB —
 * individual .ibd files grow lazily inside whatever space the OS filesystem
 * has, and there is no engine-side notion of "% full" per tablespace. What
 * operators actually care about is:
 *
 *   1. InnoDB system tablespaces (ibdata1, undo logs, redo, temporary) —
 *      size only, no pct. Growth is the signal, not fill.
 *   2. Top-N databases by logical size — answers "who's eating the disk?".
 *
 * Both queries are bounded (system tablespaces ≤ ~5 rows, per-DB capped to
 * LIMIT 30). INFORMATION_SCHEMA.TABLES is the expensive one — it can stat
 * every .frm/.ibd on disk when `innodb_stats_on_metadata=ON`, so we:
 *   - set a per-statement timeout,
 *   - scope to user schemas only,
 *   - sort by DATA+INDEX desc and cap to 30.
 *
 * No WARN/CRIT classification is produced — neither query yields a reliable
 * "% full" against a known ceiling. Severity stays OK; the tile renders
 * absolute sizes and the operator correlates with host disk pressure on the
 * Infra tile when needed.
 */
@Slf4j
public abstract class MysqlFamilySlowCollector implements SlowCollector {

    protected final int queryTimeoutSec;

    protected MysqlFamilySlowCollector(DeepGazeProperties props) {
        long slowMs = props.scheduler().slowCollectionTimeoutMs();
        long effective = Math.max(1000L, slowMs - 500L);
        this.queryTimeoutSec = Math.max(1, (int) (effective / 1000L));
    }

    /**
     * InnoDB system-level tablespaces. Two wrinkles across versions:
     *   - View name: MySQL 8 uses INNODB_TABLESPACES, MariaDB pre-10.6 uses
     *     INNODB_SYS_TABLESPACES (10.6+ has both as aliases).
     *   - SPACE_TYPE column: added in MariaDB 10.5 / MySQL 8 — pre-10.5
     *     MariaDB doesn't expose it, so filtering in SQL would ER_BAD_FIELD.
     *
     * We select only always-present columns (NAME, FILE_SIZE, ALLOCATED_SIZE)
     * and classify by NAME pattern in Java. The `%` filter skips per-table
     * tablespaces (NAME like 'mydb/mytable') which would otherwise flood the
     * tile with one row per user table.
     */
    private String systemTablespacesSql() {
        return """
                SELECT NAME           AS name,
                       FILE_SIZE      AS file_size_bytes,
                       ALLOCATED_SIZE AS allocated_bytes
                  FROM information_schema.%s
                 WHERE NAME NOT LIKE '%%/%%'
                """.formatted(tablespacesView());
    }

    /** Per-engine override — see {@link #systemTablespacesSql()} for why this differs. */
    protected abstract String tablespacesView();

    /**
     * Top databases by logical size. DATA_FREE is fragmentation, not a
     * ceiling — we surface it as a note so the operator can judge whether
     * an OPTIMIZE TABLE pass would reclaim anything. TABLE_TYPE filter is
     * important: VIEWs in information_schema.TABLES have NULL DATA_LENGTH
     * and show up as zeros that skew the sort.
     */
    private static final String DB_SIZES_SQL = """
            SELECT TABLE_SCHEMA            AS db_name,
                   SUM(DATA_LENGTH)        AS data_bytes,
                   SUM(INDEX_LENGTH)       AS index_bytes,
                   SUM(DATA_FREE)          AS free_bytes,
                   COUNT(*)                AS table_count
              FROM information_schema.TABLES
             WHERE TABLE_SCHEMA NOT IN ('information_schema', 'performance_schema', 'mysql', 'sys')
               AND TABLE_TYPE = 'BASE TABLE'
             GROUP BY TABLE_SCHEMA
             ORDER BY SUM(DATA_LENGTH) + SUM(INDEX_LENGTH) DESC
             LIMIT 30
            """;

    @Override
    public StorageSaturationDto collectSaturation(DbTargetConfig target, DataSource ds) throws SQLException {
        List<Item> items = new ArrayList<>(32);
        try (Connection c = ds.getConnection()) {
            collectSystemTablespaces(c, items);
            collectDbSizes(c, items);
        }
        return new StorageSaturationDto(
                target.id(), target.type(), Instant.now(), List.copyOf(items));
    }

    private void collectSystemTablespaces(Connection c, List<Item> out) {
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(queryTimeoutSec);
            try (ResultSet rs = s.executeQuery(systemTablespacesSql())) {
                while (rs.next()) {
                    String name     = rs.getString("name");
                    Long sizeBytes  = readLong(rs, "file_size_bytes");
                    Long allocBytes = readLong(rs, "allocated_bytes");

                    Long sizeMb  = sizeBytes  == null ? null : sizeBytes  / 1048576L;
                    Long allocMb = allocBytes == null ? null : allocBytes / 1048576L;

                    StorageType type = classifyByName(name);

                    String note = (allocMb != null && sizeMb != null && !allocMb.equals(sizeMb))
                            ? String.format("allocated %d MB (vs file %d MB)", allocMb, sizeMb)
                            : null;

                    // No "% full" concept — InnoDB grows into the OS filesystem, not into a ceiling.
                    out.add(new Item(type,
                            name == null ? "(unnamed)" : name,
                            sizeMb, sizeMb, null, null,
                            null, Severity.OK, note));
                }
            }
        } catch (SQLException e) {
            log.warn("InnoDB system tablespace saturation failed: state={} code={} msg={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage());
        }
    }

    /**
     * Pattern-based classification — works across every MariaDB/MySQL version
     * we care about without depending on the SPACE_TYPE column (missing on
     * MariaDB pre-10.5). The reserved InnoDB internal tablespace names are
     * stable across the entire 10.x / 8.x line.
     */
    private static StorageType classifyByName(String name) {
        if (name == null) return StorageType.TABLESPACE;
        String lower = name.toLowerCase();
        if (lower.contains("undo"))      return StorageType.UNDO;
        if (lower.contains("temporary")) return StorageType.TEMP;
        if (lower.contains("temp"))      return StorageType.TEMP;
        return StorageType.TABLESPACE;
    }

    private void collectDbSizes(Connection c, List<Item> out) {
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(queryTimeoutSec);
            try (ResultSet rs = s.executeQuery(DB_SIZES_SQL)) {
                while (rs.next()) {
                    String dbName   = rs.getString("db_name");
                    Long dataBytes  = readLong(rs, "data_bytes");
                    Long indexBytes = readLong(rs, "index_bytes");
                    Long freeBytes  = readLong(rs, "free_bytes");
                    int tableCount  = rs.getInt("table_count");

                    long totalBytes = (dataBytes == null ? 0L : dataBytes)
                                    + (indexBytes == null ? 0L : indexBytes);
                    Long totalMb = totalBytes == 0 ? null : totalBytes / 1048576L;
                    Long freeMb  = freeBytes  == null ? null : freeBytes  / 1048576L;

                    String note = String.format("%d table(s) · data %d MB · index %d MB%s",
                            tableCount,
                            (dataBytes  == null ? 0L : dataBytes)  / 1048576L,
                            (indexBytes == null ? 0L : indexBytes) / 1048576L,
                            (freeMb != null && freeMb > 0)
                                    ? String.format(" · %d MB fragmented", freeMb)
                                    : "");

                    out.add(new Item(StorageType.DATAFILE,
                            dbName == null ? "(unknown)" : dbName,
                            totalMb, totalMb, freeMb, null,
                            null, Severity.OK, note));
                }
            }
        } catch (SQLException e) {
            log.warn("Per-DB logical size saturation failed: state={} code={} msg={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage());
        }
    }

    private static Long readLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
