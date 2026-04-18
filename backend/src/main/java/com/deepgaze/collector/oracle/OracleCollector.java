package com.deepgaze.collector.oracle;

import com.deepgaze.collector.Collector;
import com.deepgaze.collector.JdbcUtils;
import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.model.MetricSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Oracle metric collection from in-memory dynamic performance views.
 * Avoids v$active_session_history (licensed feature) — uses a per-second snapshot
 * of v$session as a free, lightweight substitute.
 */
@Slf4j
@Component
public class OracleCollector implements Collector {

    private final int queryTimeoutSec;

    public OracleCollector(DeepGazeProperties props) {
        this.queryTimeoutSec = JdbcUtils.toQueryTimeoutSec(props.scheduler().collectionTimeoutMs());
    }

    @Override
    public DbType supports() { return DbType.ORACLE; }

    /** v$session aggregated by status × wait_class — the free ASH-substitute. */
    private static final String SESSIONS_SQL = """
            SELECT NVL(status, 'UNKNOWN')      AS status,
                   NVL(wait_class, 'UNKNOWN')  AS wait_class,
                   COUNT(*)                    AS cnt
              FROM v$session
             WHERE type = 'USER'
             GROUP BY status, wait_class
            """;

    /** Whitelisted cumulative counters from v$sysstat — small, key-value shape. */
    private static final String SYSSTAT_SQL = """
            SELECT name, value
              FROM v$sysstat
             WHERE name IN (
               'execute count',
               'user commits',
               'user rollbacks',
               'parse count (total)',
               'parse count (hard)',
               'session logical reads',
               'physical reads',
               'physical writes',
               'redo size',
               'bytes received via SQL*Net from client',
               'bytes sent via SQL*Net to client',
               'CPU used by this session'
             )
            """;

    /**
     * group_id = 3 → 15-second metrics window (the freshest aggregate Oracle exposes).
     * Polling at 1Hz will see the same row repeated until Oracle rolls a new window;
     * dedupe is a frontend concern.
     */
    private static final String SYSMETRIC_SQL = """
            SELECT metric_name, value, metric_unit, begin_time, end_time
              FROM v$sysmetric
             WHERE group_id = 3
               AND metric_name IN (
                 'CPU Usage Per Sec',
                 'User Transaction Per Sec',
                 'Executions Per Sec',
                 'Logical Reads Per Sec',
                 'Physical Reads Per Sec',
                 'Database Time Per Sec',
                 'Average Active Sessions',
                 'Host CPU Utilization (%)'
               )
            """;

    /**
     * Top 10 SQL by elapsed time, restricted to the last minute to keep v$sql scan small.
     * SUBSTR caps payload size; full text is in v$sqltext when needed (not pulled per tick).
     */
    private static final String TOP_SQL = """
            SELECT *
              FROM (SELECT sql_id,
                           executions,
                           elapsed_time,
                           cpu_time,
                           buffer_gets,
                           rows_processed,
                           SUBSTR(sql_text, 1, 200) AS sql_text
                      FROM v$sql
                     WHERE last_active_time > SYSDATE - 1/24/60
                     ORDER BY elapsed_time DESC)
             WHERE ROWNUM <= 10
            """;

    @Override
    public List<MetricSnapshot> collect(DbTargetConfig target, DataSource ds) throws SQLException {
        List<MetricSnapshot> out = new ArrayList<>(4);
        try (Connection c = ds.getConnection()) {
            runOne(target, c, "sessions",  SESSIONS_SQL).ifPresent(out::add);
            runOne(target, c, "sysstat",   SYSSTAT_SQL).ifPresent(out::add);
            runOne(target, c, "sysmetric", SYSMETRIC_SQL).ifPresent(out::add);
            runOne(target, c, "topSql",    TOP_SQL).ifPresent(out::add);
        } catch (SQLException e) {
            log.warn("Oracle connection acquisition failed: target={} state={} code={} msg={}",
                    target.id(), e.getSQLState(), e.getErrorCode(), e.getMessage());
            throw e;
        }
        return out;
    }

    private Optional<MetricSnapshot> runOne(DbTargetConfig target, Connection c, String group, String sql) {
        try {
            return Optional.of(JdbcUtils.runQuery(target, c, queryTimeoutSec, group, sql));
        } catch (SQLException e) {
            log.warn("Oracle query failed: target={} group={} state={} code={} msg={}",
                    target.id(), group, e.getSQLState(), e.getErrorCode(), e.getMessage());
            return Optional.empty();
        }
    }
}
