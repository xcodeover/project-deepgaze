package com.deepgaze.collector.mysql;

import com.deepgaze.collector.Collector;
import com.deepgaze.collector.JdbcUtils;
import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.MetricSnapshot;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared collection logic for the MySQL/MariaDB family — both expose the same
 * SHOW GLOBAL STATUS / VARIABLES, information_schema.processlist, and (when
 * enabled) performance_schema.events_statements_summary_by_digest views.
 *
 * Concrete subclasses only declare which DbType they support so that Spring
 * can register one bean per engine and the CollectorRegistry can dispatch
 * cleanly per target.
 */
@Slf4j
public abstract class MysqlFamilyCollector implements Collector {

    private final int queryTimeoutSec;

    protected MysqlFamilyCollector(DeepGazeProperties props) {
        this.queryTimeoutSec = JdbcUtils.toQueryTimeoutSec(props.scheduler().collectionTimeoutMs());
    }

    /** Filtered server-side to keep payload small (~25 keys vs ~600). */
    private static final String STATUS_SQL = """
            SHOW GLOBAL STATUS WHERE Variable_name IN (
              'Threads_running', 'Threads_connected', 'Threads_created', 'Threads_cached',
              'Questions', 'Queries', 'Slow_queries',
              'Com_select', 'Com_insert', 'Com_update', 'Com_delete', 'Com_commit', 'Com_rollback',
              'Innodb_buffer_pool_reads', 'Innodb_buffer_pool_read_requests',
              'Innodb_rows_read', 'Innodb_rows_inserted', 'Innodb_rows_updated', 'Innodb_rows_deleted',
              'Bytes_received', 'Bytes_sent',
              'Connections', 'Aborted_connects', 'Aborted_clients',
              'Open_tables', 'Opened_tables'
            )
            """;

    /** Variables rarely change — small whitelist tracks the ones an operator actually cares about. */
    private static final String VARIABLES_SQL = """
            SHOW GLOBAL VARIABLES WHERE Variable_name IN (
              'max_connections',
              'innodb_buffer_pool_size',
              'long_query_time',
              'wait_timeout',
              'interactive_timeout',
              'max_allowed_packet'
            )
            """;

    /** Active sessions (skip Sleep). LEFT(...) caps payload. */
    private static final String PROCESSLIST_SQL = """
            SELECT id, user, host, db, command, time, state, LEFT(info, 200) AS info
              FROM information_schema.processlist
             WHERE command <> 'Sleep'
             LIMIT 100
            """;

    /**
     * Top digests by total wait time. Requires performance_schema=ON; if disabled
     * the query throws ER_NO_SUCH_TABLE and we degrade gracefully (logged + skipped).
     */
    private static final String TOP_DIGESTS_SQL = """
            SELECT DIGEST                              AS digest,
                   SUBSTRING(DIGEST_TEXT, 1, 200)      AS digest_text,
                   COUNT_STAR                          AS count_star,
                   SUM_TIMER_WAIT                      AS sum_timer_wait,
                   AVG_TIMER_WAIT                      AS avg_timer_wait,
                   SUM_ROWS_EXAMINED                   AS sum_rows_examined,
                   SUM_ROWS_SENT                       AS sum_rows_sent
              FROM performance_schema.events_statements_summary_by_digest
             WHERE DIGEST IS NOT NULL
             ORDER BY SUM_TIMER_WAIT DESC
             LIMIT 10
            """;

    @Override
    public final List<MetricSnapshot> collect(DbTargetConfig target, DataSource ds) throws SQLException {
        List<MetricSnapshot> out = new ArrayList<>(4);
        try (Connection c = ds.getConnection()) {
            runOne(target, c, "status",      STATUS_SQL).ifPresent(out::add);
            runOne(target, c, "variables",   VARIABLES_SQL).ifPresent(out::add);
            runOne(target, c, "processlist", PROCESSLIST_SQL).ifPresent(out::add);
            runOne(target, c, "topDigests",  TOP_DIGESTS_SQL).ifPresent(out::add);
        } catch (SQLException e) {
            log.warn("{} connection acquisition failed: target={} state={} code={} msg={}",
                    supports(), target.id(), e.getSQLState(), e.getErrorCode(), e.getMessage());
            throw e;
        }
        return out;
    }

    private Optional<MetricSnapshot> runOne(DbTargetConfig target, Connection c, String group, String sql) {
        try {
            return Optional.of(JdbcUtils.runQuery(target, c, queryTimeoutSec, group, sql));
        } catch (SQLException e) {
            log.warn("{} query failed: target={} group={} state={} code={} msg={}",
                    supports(), target.id(), group, e.getSQLState(), e.getErrorCode(), e.getMessage());
            return Optional.empty();
        }
    }
}
