package com.deepgaze.history;

import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.DbType;
import com.deepgaze.model.MetricSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embedded SQLite writer for the V9 Time Machine. One physical file at
 * {@code deepgaze.history.db-path}, opened in WAL mode so reads never
 * block the writer. All mutation goes through a single Connection guarded
 * by this object's monitor — {@code MetricsPersisterService} runs a single
 * dedicated writer thread, so lock contention is only between batch inserts
 * and the periodic purge.
 *
 * Schema is deliberately narrow:
 *   metric_snapshot(id, target_id, target_name, engine_type, ts_epoch_ms, group_key, rows_json)
 * The {@code rows} payload is persisted as a Jackson-serialised JSON blob
 * because the three engines each emit a different row shape per metric
 * group; reifying that into columns would bloat the schema for no gain.
 * Replay does memory-side filtering after {@code ts_epoch_ms} / {@code group_key}
 * narrow the scan via the covering index.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "deepgaze.history", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HistoryStore {

    private static final String DDL_SNAPSHOT = """
            CREATE TABLE IF NOT EXISTS metric_snapshot (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                target_id    TEXT    NOT NULL,
                target_name  TEXT,
                engine_type  TEXT    NOT NULL,
                ts_epoch_ms  INTEGER NOT NULL,
                group_key    TEXT    NOT NULL,
                rows_json    TEXT    NOT NULL
            )
            """;

    private static final String DDL_IDX_QUERY = """
            CREATE INDEX IF NOT EXISTS idx_ms_query
                ON metric_snapshot(target_id, group_key, ts_epoch_ms)
            """;

    private static final String DDL_IDX_PURGE = """
            CREATE INDEX IF NOT EXISTS idx_ms_purge
                ON metric_snapshot(ts_epoch_ms)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO metric_snapshot
                (target_id, target_name, engine_type, ts_epoch_ms, group_key, rows_json)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final DeepGazeProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private Connection conn;

    public HistoryStore(DeepGazeProperties props) {
        this.props = props;
    }

    @PostConstruct
    public synchronized void init() throws SQLException {
        String path = props.history().dbPath();
        File f = new File(path);
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("Could not create history parent dir: {}", parent);
        }
        String url = "jdbc:sqlite:" + f.getAbsolutePath();
        this.conn = DriverManager.getConnection(url);
        // PRAGMA journal_mode=WAL must run OUTSIDE a transaction — SQLite
        // rejects the switch if any tx is already open. Run pragmas +
        // DDL in autocommit, then flip to manual-commit for batch INSERTs.
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA temp_store=MEMORY");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute(DDL_SNAPSHOT);
            s.execute(DDL_IDX_QUERY);
            s.execute(DDL_IDX_PURGE);
        }
        this.conn.setAutoCommit(false);
        log.info("HistoryStore ready: path={} retentionHours={}",
                f.getAbsolutePath(), props.history().retentionHours());
    }

    /**
     * Persists a batch in a single transaction. Returns the number of rows
     * actually written (a row with an unserialisable payload is skipped
     * with a warn so one poison snapshot can't kill the writer thread).
     */
    public synchronized int insertBatch(List<MetricSnapshot> batch) {
        if (conn == null || batch == null || batch.isEmpty()) return 0;
        int written = 0;
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            for (MetricSnapshot snap : batch) {
                String rowsJson;
                try {
                    rowsJson = json.writeValueAsString(snap.rows());
                } catch (JsonProcessingException je) {
                    log.warn("Dropping snapshot — rows not serialisable: target={} group={} err={}",
                            snap.targetId(), snap.metricGroup(), je.getMessage());
                    continue;
                }
                ps.setString(1, snap.targetId());
                ps.setString(2, snap.targetName());
                ps.setString(3, snap.type() == null ? null : snap.type().name());
                ps.setLong  (4, snap.timestamp() == null ? System.currentTimeMillis() : snap.timestamp().toEpochMilli());
                ps.setString(5, snap.metricGroup());
                ps.setString(6, rowsJson);
                ps.addBatch();
                written++;
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            log.warn("History batch insert failed ({} rows): {}", batch.size(), e.getMessage());
            try { conn.rollback(); } catch (SQLException ignored) {}
            return 0;
        }
        return written;
    }

    /**
     * Deletes rows older than the configured retention. Called on a fixed
     * cadence from {@link MetricsPersisterService}. Returns the row count
     * deleted so the caller can log a one-line summary.
     */
    public synchronized int purgeOlderThan(long cutoffEpochMs) {
        if (conn == null) return 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM metric_snapshot WHERE ts_epoch_ms < ?")) {
            ps.setLong(1, cutoffEpochMs);
            int deleted = ps.executeUpdate();
            conn.commit();
            return deleted;
        } catch (SQLException e) {
            log.warn("History purge failed: {}", e.getMessage());
            try { conn.rollback(); } catch (SQLException ignored) {}
            return 0;
        }
    }

    /**
     * Point-in-time read: for each {@code group_key} observed on this target,
     * returns the single snapshot whose {@code ts_epoch_ms} is the latest at or
     * before {@code asOfMs}. One index seek per group via {@code idx_ms_query}
     * (target_id, group_key, ts_epoch_ms) — fast enough to serve interactive
     * slider scrubbing.
     *
     * Returns an empty list when the target has no history yet (e.g. fresh
     * DB or a timestamp that pre-dates collection start).
     */
    public synchronized List<MetricSnapshot> asOfSnapshot(String targetId, long asOfMs) {
        if (conn == null || targetId == null) return List.of();
        String sql = """
                SELECT s.target_id, s.target_name, s.engine_type, s.ts_epoch_ms, s.group_key, s.rows_json
                  FROM metric_snapshot s
                  INNER JOIN (
                      SELECT group_key, MAX(ts_epoch_ms) AS max_ts
                        FROM metric_snapshot
                       WHERE target_id = ? AND ts_epoch_ms <= ?
                       GROUP BY group_key
                  ) m
                  ON s.group_key = m.group_key AND s.ts_epoch_ms = m.max_ts
                 WHERE s.target_id = ?
                """;
        List<MetricSnapshot> out = new ArrayList<>();
        TypeReference<List<Map<String, Object>>> rowsType = new TypeReference<>() {};
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetId);
            ps.setLong  (2, asOfMs);
            ps.setString(3, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rowsJson = rs.getString("rows_json");
                    List<Map<String, Object>> rows;
                    try {
                        rows = rowsJson == null || rowsJson.isBlank()
                                ? List.of()
                                : json.readValue(rowsJson, rowsType);
                    } catch (Exception de) {
                        log.warn("Skipping corrupt snapshot row: target={} group={} err={}",
                                targetId, rs.getString("group_key"), de.getMessage());
                        continue;
                    }
                    String engineType = rs.getString("engine_type");
                    DbType type = null;
                    try { type = engineType == null ? null : DbType.valueOf(engineType); }
                    catch (IllegalArgumentException ignored) {}
                    out.add(new MetricSnapshot(
                            rs.getString("target_id"),
                            rs.getString("target_name"),
                            type,
                            Instant.ofEpochMilli(rs.getLong("ts_epoch_ms")),
                            rs.getString("group_key"),
                            rows
                    ));
                }
            }
        } catch (SQLException e) {
            log.warn("History asOfSnapshot failed: target={} asOfMs={} err={}",
                    targetId, asOfMs, e.getMessage());
        }
        return out;
    }

    /**
     * Returns the [min, max] {@code ts_epoch_ms} bounds available for a
     * target so the scrubber UI can size its slider to the actual retention
     * window (may be shorter than the configured retention during the first
     * hours after boot). Returns {@code null} when the target has no rows.
     */
    public synchronized long[] rangeBounds(String targetId) {
        if (conn == null || targetId == null) return null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT MIN(ts_epoch_ms), MAX(ts_epoch_ms) FROM metric_snapshot WHERE target_id = ?")) {
            ps.setString(1, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long min = rs.getLong(1);
                    if (rs.wasNull()) return null;
                    long max = rs.getLong(2);
                    return new long[] { min, max };
                }
            }
        } catch (SQLException e) {
            log.warn("History rangeBounds failed: target={} err={}", targetId, e.getMessage());
        }
        return null;
    }

    @PreDestroy
    public synchronized void close() {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("HistoryStore close error: {}", e.getMessage());
        } finally {
            conn = null;
        }
    }
}
