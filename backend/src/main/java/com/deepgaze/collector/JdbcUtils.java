package com.deepgaze.collector;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.MetricSnapshot;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JdbcUtils {

    private JdbcUtils() {}

    /** Convert the scheduler's collection budget into a per-statement timeout in seconds. */
    public static int toQueryTimeoutSec(long collectionTimeoutMs) {
        // 500ms headroom so the driver cancels before the CompletableFuture orTimeout fires
        long ms = Math.max(1000L, collectionTimeoutMs - 500L);
        return Math.max(1, (int) (ms / 1000L));
    }

    /**
     * Execute a single read-only query on an already-borrowed connection and shape
     * the ResultSet into a MetricSnapshot. The Connection is intentionally NOT
     * closed here — collectors borrow one connection per tick and reuse it for
     * all metric groups.
     */
    public static MetricSnapshot runQuery(
            DbTargetConfig target,
            Connection conn,
            int queryTimeoutSec,
            String group,
            String sql) throws SQLException {

        try (Statement s = conn.createStatement()) {
            s.setQueryTimeout(queryTimeoutSec);
            s.setFetchSize(500);
            try (ResultSet rs = s.executeQuery(sql)) {
                return new MetricSnapshot(
                        target.id(),
                        target.displayName(),
                        target.type(),
                        Instant.now(),
                        group,
                        rowsOf(rs)
                );
            }
        }
    }

    public static List<Map<String, Object>> rowsOf(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        List<Map<String, Object>> out = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(cols);
            for (int i = 1; i <= cols; i++) {
                String key = md.getColumnLabel(i);
                Object val = rs.getObject(i);
                if (val instanceof Timestamp ts) {
                    val = ts.toInstant();
                }
                row.put(key, val);
            }
            out.add(row);
        }
        return out;
    }
}
