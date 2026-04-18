package com.deepgaze.collector.mysql;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper for the MariaDB collector. SQL lives here as @Select
 * annotations — separated from the Java orchestration in MariaDbCollector.
 *
 * Default statement timeout is set on the per-target Configuration in
 * MyBatisFactoryRegistry, so individual @Select methods do not need an
 * explicit @Options(timeout = ...) override.
 *
 * Result type is List<Map<String, Object>> to keep the row shape compatible
 * with MetricSnapshot.rows without forcing a typed POJO per query.
 */
@Mapper
public interface MariaDbMapper {

    /** Filtered server-side to ~25 keys vs ~600 SHOW GLOBAL STATUS rows. */
    @Select("""
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
            """)
    List<Map<String, Object>> selectStatus();

    /** Whitelisted variables — they rarely change but we surface the meaningful ones. */
    @Select("""
            SHOW GLOBAL VARIABLES WHERE Variable_name IN (
              'max_connections',
              'innodb_buffer_pool_size',
              'long_query_time',
              'wait_timeout',
              'interactive_timeout',
              'max_allowed_packet'
            )
            """)
    List<Map<String, Object>> selectVariables();

    /** Active sessions only (skip Sleep). LEFT(...) caps payload. */
    @Select("""
            SELECT id, user, host, db, command, time, state, LEFT(info, 200) AS info
              FROM information_schema.processlist
             WHERE command <> 'Sleep'
             LIMIT 100
            """)
    List<Map<String, Object>> selectProcesslist();

    /**
     * Top digests by total wait time. Requires performance_schema=ON; if
     * disabled the query throws ER_NO_SUCH_TABLE and the collector skips it
     * gracefully (other groups still return).
     */
    @Select("""
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
            """)
    List<Map<String, Object>> selectTopDigests();
}
