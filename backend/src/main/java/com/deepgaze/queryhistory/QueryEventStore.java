package com.deepgaze.queryhistory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedded SQLite writer for the per-target query-history search surface.
 * Parallels {@link com.deepgaze.history.HistoryStore} — separate file (so the
 * digest-text LIKE scans don't thrash the big metric_snapshot table), WAL
 * mode, single guarded Connection.
 *
 * The schema is narrow and search-optimised:
 *   PK by ts for purge range scans, composite index (target_id, ts_epoch_ms)
 *   for the primary search path. No FTS — LIKE on digest_text is acceptable
 *   at retention-bound row counts (~170k / 48h on the reference config), and
 *   sidesteps the FTS vs. purge coordination problem.
 */
@Slf4j
@Component
public class QueryEventStore {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS query_event (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                target_id         TEXT    NOT NULL,
                target_name       TEXT,
                engine_type       TEXT    NOT NULL,
                ts_epoch_ms       INTEGER NOT NULL,
                digest            TEXT,
                digest_text       TEXT,
                count_star        INTEGER,
                sum_rows_sent     INTEGER,
                sum_rows_examined INTEGER,
                avg_timer_wait    REAL,
                sum_timer_wait    REAL,
                source_group      TEXT    NOT NULL
            )
            """;

    private static final String DDL_IDX_SEARCH = """
            CREATE INDEX IF NOT EXISTS idx_qe_search
                ON query_event(target_id, ts_epoch_ms)
            """;

    private static final String DDL_IDX_DIGEST = """
            CREATE INDEX IF NOT EXISTS idx_qe_digest
                ON query_event(target_id, digest, ts_epoch_ms)
            """;

    private static final String DDL_IDX_PURGE = """
            CREATE INDEX IF NOT EXISTS idx_qe_purge
                ON query_event(ts_epoch_ms)
            """;

    private static final String INSERT = """
            INSERT INTO query_event (
                target_id, target_name, engine_type, ts_epoch_ms,
                digest, digest_text, count_star, sum_rows_sent,
                sum_rows_examined, avg_timer_wait, sum_timer_wait, source_group
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final String dbPath;
    private Connection conn;

    public QueryEventStore(
            @Value("${deepgaze.query-history.db-path:./data/deepgaze-query-events.db}") String dbPath
    ) {
        this.dbPath = dbPath;
    }

    @PostConstruct
    public synchronized void init() throws SQLException {
        File f = new File(dbPath);
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("Could not create query-history parent dir: {}", parent);
        }
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA temp_store=MEMORY");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute(DDL);
            s.execute(DDL_IDX_SEARCH);
            s.execute(DDL_IDX_DIGEST);
            s.execute(DDL_IDX_PURGE);
        }
        this.conn.setAutoCommit(false);
        log.info("QueryEventStore ready: path={}", f.getAbsolutePath());
    }

    public synchronized int insertBatch(List<QueryEvent> batch) {
        if (conn == null || batch == null || batch.isEmpty()) return 0;
        int written = 0;
        try (PreparedStatement ps = conn.prepareStatement(INSERT)) {
            for (QueryEvent e : batch) {
                ps.setString(1, e.targetId());
                ps.setString(2, e.targetName());
                ps.setString(3, e.engineType());
                ps.setLong  (4, e.tsEpochMs());
                ps.setString(5, e.digest());
                ps.setString(6, e.digestText());
                setNullableLong  (ps, 7,  e.countStar());
                setNullableLong  (ps, 8,  e.sumRowsSent());
                setNullableLong  (ps, 9,  e.sumRowsExamined());
                setNullableDouble(ps, 10, e.avgTimerWait());
                setNullableDouble(ps, 11, e.sumTimerWait());
                ps.setString(12, e.sourceGroup());
                ps.addBatch();
                written++;
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException ex) {
            log.warn("QueryEvent batch insert failed ({} rows): {}", batch.size(), ex.getMessage());
            try { conn.rollback(); } catch (SQLException ignored) {}
            return 0;
        }
        return written;
    }

    public synchronized int purgeOlderThan(long cutoffEpochMs) {
        if (conn == null) return 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM query_event WHERE ts_epoch_ms < ?")) {
            ps.setLong(1, cutoffEpochMs);
            int deleted = ps.executeUpdate();
            conn.commit();
            return deleted;
        } catch (SQLException e) {
            log.warn("QueryEvent purge failed: {}", e.getMessage());
            try { conn.rollback(); } catch (SQLException ignored) {}
            return 0;
        }
    }

    /**
     * Bucketed count-over-time for a set of digests. Used by the Query History
     * modal to render inline sparklines next to each row.
     *
     * Computes floor((ts - fromMs) / bucketMs) server-side so the UI can render
     * a fixed-width chart per digest without scanning the full page again.
     */
    public synchronized List<TrendPoint> trend(TrendQuery q) {
        if (conn == null || q.digests() == null || q.digests().isEmpty()) return List.of();
        int maxDigests = Math.min(q.digests().size(), 200);
        long bucketMs  = Math.max(1_000L, q.bucketMs());

        StringBuilder sql = new StringBuilder()
                .append("SELECT digest, ")
                .append("  CAST((ts_epoch_ms - ?) / ? AS INTEGER) AS bucket, ")
                .append("  COUNT(*)             AS row_count, ")
                .append("  SUM(count_star)      AS sum_count, ")
                .append("  AVG(avg_timer_wait)  AS avg_lat ")
                .append("FROM query_event ")
                .append("WHERE target_id = ? AND ts_epoch_ms BETWEEN ? AND ? ")
                .append("  AND digest IN (");
        for (int i = 0; i < maxDigests; i++) sql.append(i == 0 ? "?" : ",?");
        sql.append(") GROUP BY digest, bucket");

        List<TrendPoint> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setLong(i++, q.fromMs());
            ps.setLong(i++, bucketMs);
            ps.setString(i++, q.targetId());
            ps.setLong(i++, q.fromMs());
            ps.setLong(i++, q.toMs());
            for (int d = 0; d < maxDigests; d++) ps.setString(i++, q.digests().get(d));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TrendPoint(
                            rs.getString("digest"),
                            rs.getInt   ("bucket"),
                            rs.getLong  ("row_count"),
                            longOrNull(rs, "sum_count"),
                            doubleOrNull(rs, "avg_lat")
                    ));
                }
            }
        } catch (SQLException e) {
            log.warn("QueryEvent.trend failed: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    public synchronized SearchResult search(SearchQuery q) {
        if (conn == null) return new SearchResult(0L, List.of());

        // Build WHERE + args in one pass.
        StringBuilder where = new StringBuilder("WHERE target_id = ? AND ts_epoch_ms BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(q.targetId());
        args.add(q.fromMs());
        args.add(q.toMs());

        if (q.textQuery() != null && !q.textQuery().isBlank()) {
            where.append(" AND (digest_text LIKE ? OR digest = ?)");
            args.add("%" + q.textQuery() + "%");
            args.add(q.textQuery());
        }
        if (q.sourceGroup() != null && !q.sourceGroup().isBlank()) {
            where.append(" AND source_group = ?");
            args.add(q.sourceGroup());
        }

        long total;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM query_event " + where)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                total = rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            log.warn("QueryEvent.search count failed: {}", e.getMessage());
            return new SearchResult(0L, List.of());
        }

        String pageSql = "SELECT target_id, target_name, engine_type, ts_epoch_ms, digest, "
                       + "digest_text, count_star, sum_rows_sent, sum_rows_examined, "
                       + "avg_timer_wait, sum_timer_wait, source_group "
                       + "FROM query_event " + where
                       + " ORDER BY ts_epoch_ms DESC LIMIT ? OFFSET ?";

        List<QueryEvent> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(pageSql)) {
            int i = bind(ps, args);
            ps.setInt(i++, Math.max(1, Math.min(q.limit(), 500)));
            ps.setInt(i,   Math.max(0, q.offset()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new QueryEvent(
                            rs.getString("target_id"),
                            rs.getString("target_name"),
                            rs.getString("engine_type"),
                            rs.getLong  ("ts_epoch_ms"),
                            rs.getString("digest"),
                            rs.getString("digest_text"),
                            longOrNull(rs, "count_star"),
                            longOrNull(rs, "sum_rows_sent"),
                            longOrNull(rs, "sum_rows_examined"),
                            doubleOrNull(rs, "avg_timer_wait"),
                            doubleOrNull(rs, "sum_timer_wait"),
                            rs.getString("source_group")
                    ));
                }
            }
        } catch (SQLException e) {
            log.warn("QueryEvent.search page failed: {}", e.getMessage());
            return new SearchResult(total, List.of());
        }

        return new SearchResult(total, items);
    }

    @PreDestroy
    public synchronized void close() {
        if (conn == null) return;
        try { conn.close(); } catch (SQLException e) {
            log.warn("QueryEventStore close error: {}", e.getMessage());
        }
        conn = null;
    }

    /* ---- binding helpers ---- */

    private static int bind(PreparedStatement ps, List<Object> args) throws SQLException {
        int i = 1;
        for (Object a : args) {
            if (a instanceof Long l)        ps.setLong   (i, l);
            else if (a instanceof Integer n) ps.setInt    (i, n);
            else                             ps.setString (i, String.valueOf(a));
            i++;
        }
        return i;
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.INTEGER); else ps.setLong(idx, v);
    }

    private static void setNullableDouble(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.REAL); else ps.setDouble(idx, v);
    }

    private static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Double doubleOrNull(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    public record SearchQuery(
            String targetId,
            long fromMs,
            long toMs,
            String textQuery,
            String sourceGroup,
            int limit,
            int offset
    ) {}

    public record SearchResult(long total, List<QueryEvent> items) {}

    public record TrendQuery(
            String targetId,
            long fromMs,
            long toMs,
            long bucketMs,
            List<String> digests
    ) {}

    public record TrendPoint(
            String digest,
            int    bucket,
            long   rowCount,
            Long   sumCount,
            Double avgLatency
    ) {}
}
