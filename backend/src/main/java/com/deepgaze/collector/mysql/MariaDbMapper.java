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

    /**
     * Filtered server-side to ~25 keys vs ~600 global-status rows. Uses
     * information_schema.GLOBAL_STATUS (not SHOW GLOBAL STATUS) because the
     * latter rejects column aliases, and the frontend's generic counter chart
     * expects lower-case `variable_name` / `value` keys.
     */
    @Select("""
            SELECT VARIABLE_NAME  AS variable_name,
                   VARIABLE_VALUE AS value
              FROM information_schema.GLOBAL_STATUS
             WHERE VARIABLE_NAME IN (
               'THREADS_RUNNING', 'THREADS_CONNECTED', 'THREADS_CREATED', 'THREADS_CACHED',
               'QUESTIONS', 'QUERIES', 'SLOW_QUERIES',
               'COM_SELECT', 'COM_INSERT', 'COM_UPDATE', 'COM_DELETE', 'COM_COMMIT', 'COM_ROLLBACK',
               'INNODB_BUFFER_POOL_READS', 'INNODB_BUFFER_POOL_READ_REQUESTS',
               'INNODB_BUFFER_POOL_PAGES_DIRTY', 'INNODB_BUFFER_POOL_PAGES_TOTAL',
               'INNODB_ROWS_READ', 'INNODB_ROWS_INSERTED', 'INNODB_ROWS_UPDATED', 'INNODB_ROWS_DELETED',
               'INNODB_ROW_LOCK_WAITS', 'INNODB_ROW_LOCK_TIME',
               'INNODB_LOG_WRITES',
               'BYTES_RECEIVED', 'BYTES_SENT',
               'CONNECTIONS', 'ABORTED_CONNECTS', 'ABORTED_CLIENTS',
               'OPEN_TABLES', 'OPENED_TABLES',
               'TABLE_OPEN_CACHE_HITS', 'TABLE_OPEN_CACHE_MISSES', 'TABLE_OPEN_CACHE_OVERFLOWS',
               'CREATED_TMP_TABLES', 'CREATED_TMP_DISK_TABLES',
               'UPTIME'
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

    /**
     * Session count grouped by COMMAND. Columns are named `status` / `cnt`
     * to match the generic SessionsChart contract (status-bucketed counts
     * over time). All commands including Sleep are returned so the stacked
     * area reflects total session count, not just active ones.
     *
     * The inner LIMIT 5000 is a safety rail, not a normal-path cap. On a
     * well-behaved server the processlist has tens to hundreds of rows and
     * the cap never fires. On a pathological system with tens of thousands
     * of idle connections, it bounds the per-tick scan cost so the 1s
     * fast loop doesn't turn this one query into the dominant workload.
     */
    @Select("""
            SELECT status, COUNT(*) AS cnt
              FROM (
                SELECT COMMAND AS status
                  FROM information_schema.processlist
                 LIMIT 5000
              ) pl
             GROUP BY status
            """)
    List<Map<String, Object>> selectSessions();

    /**
     * ASH-style snapshot of active foreground sessions bucketed by wait class
     * (File I/O, Lock, Mutex, Network…) or "CPU" when the thread is not in a
     * wait event. Output shape matches SessionsChart: `status` + `cnt`.
     * Sleep connections are excluded — ASH only counts active sessions.
     */
    @Select("""
            SELECT IFNULL(wait_class, 'CPU') AS status,
                   COUNT(*)                  AS cnt
              FROM (
                SELECT CASE
                         WHEN w.EVENT_NAME IS NULL
                           OR w.END_EVENT_ID IS NOT NULL             THEN NULL
                         WHEN w.EVENT_NAME LIKE 'wait/io/file/%'     THEN 'File I/O'
                         WHEN w.EVENT_NAME LIKE 'wait/io/table/%'    THEN 'Table I/O'
                         WHEN w.EVENT_NAME LIKE 'wait/io/socket/%'   THEN 'Network I/O'
                         WHEN w.EVENT_NAME LIKE 'wait/lock/%'        THEN 'Lock'
                         WHEN w.EVENT_NAME LIKE 'wait/synch/mutex/%' THEN 'Mutex'
                         WHEN w.EVENT_NAME LIKE 'wait/synch/rwlock/%' THEN 'RWLock'
                         WHEN w.EVENT_NAME LIKE 'wait/synch/cond/%'  THEN 'Cond'
                         WHEN w.EVENT_NAME LIKE 'wait/synch/%'       THEN 'Sync'
                         ELSE 'Other'
                       END AS wait_class
                  FROM performance_schema.threads t
                  LEFT JOIN performance_schema.events_waits_current w
                    ON w.THREAD_ID = t.THREAD_ID
                 WHERE t.TYPE = 'FOREGROUND'
                   AND t.PROCESSLIST_ID IS NOT NULL
                   AND t.PROCESSLIST_COMMAND <> 'Sleep'
              ) sub
             GROUP BY status
            """)
    List<Map<String, Object>> selectActiveSessions();

    /**
     * Top wait events by cumulative wait time. Idle and conditional-variable
     * waits are filtered out — they dominate SUM_TIMER_WAIT but do not reflect
     * actual work. Frontend computes per-tick delta from two adjacent rows.
     */
    @Select("""
            SELECT EVENT_NAME      AS event_name,
                   COUNT_STAR      AS count_star,
                   SUM_TIMER_WAIT  AS sum_timer_wait,
                   AVG_TIMER_WAIT  AS avg_timer_wait
              FROM performance_schema.events_waits_summary_global_by_event_name
             WHERE EVENT_NAME NOT LIKE 'idle%'
               AND EVENT_NAME NOT LIKE 'wait/synch/cond/%'
               AND COUNT_STAR > 0
             ORDER BY SUM_TIMER_WAIT DESC
             LIMIT 20
            """)
    List<Map<String, Object>> selectTopWaits();

    /**
     * Current blocker/waiter pairs from InnoDB row-lock contention. Uses
     * information_schema.INNODB_LOCK_WAITS (MariaDB-native — performance_schema
     * .data_lock_waits is MySQL 8 / MariaDB 10.6+ only). Joined to INNODB_TRX
     * on both sides for the SQL text, and to PROCESSLIST for user/host.
     * Returns zero rows when nothing is blocked (the common case).
     */
    @Select("""
            SELECT r.trx_id                  AS waiting_trx,
                   r.trx_mysql_thread_id     AS waiting_process_id,
                   wp.USER                   AS waiting_user,
                   wp.HOST                   AS waiting_host,
                   wp.TIME                   AS waiting_secs,
                   LEFT(r.trx_query, 160)    AS waiting_query,
                   b.trx_id                  AS blocking_trx,
                   b.trx_mysql_thread_id     AS blocking_process_id,
                   bp.USER                   AS blocking_user,
                   bp.HOST                   AS blocking_host,
                   bp.TIME                   AS blocking_secs,
                   LEFT(b.trx_query, 160)    AS blocking_query
              FROM information_schema.INNODB_LOCK_WAITS w
              JOIN information_schema.INNODB_TRX r
                ON r.trx_id = w.requesting_trx_id
              JOIN information_schema.INNODB_TRX b
                ON b.trx_id = w.blocking_trx_id
              LEFT JOIN information_schema.PROCESSLIST wp
                ON wp.ID = r.trx_mysql_thread_id
              LEFT JOIN information_schema.PROCESSLIST bp
                ON bp.ID = b.trx_mysql_thread_id
             LIMIT 100
            """)
    List<Map<String, Object>> selectBlockers();

    /**
     * Top N non-Sleep sessions for the drill-down table. Distinct from
     * selectSessions (aggregated by command) — these are individual rows
     * the UI can click. Truncated SQL (LEFT(..., 200)) keeps snapshots
     * small; the SessionDetailService refetches full text on demand.
     */
    @Select("""
            SELECT ID       AS id,
                   USER     AS user,
                   HOST     AS host,
                   DB       AS db,
                   COMMAND  AS command,
                   TIME     AS time_secs,
                   STATE    AS state,
                   LEFT(INFO, 200) AS info
              FROM information_schema.processlist
             WHERE COMMAND <> 'Sleep'
             ORDER BY TIME DESC
             LIMIT 25
            """)
    List<Map<String, Object>> selectProcesslistTop();

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

    /**
     * Candidate slow / unindexed statements. Ordered by average latency
     * (AVG_TIMER_WAIT) rather than cumulative wait so a rare-but-awful
     * query surfaces even when dwarfed by high-frequency fast ones.
     *
     * `scan_ratio` = examined / sent. A healthy indexed lookup is ≤ 2;
     * full-scan-and-filter queries balloon to hundreds or thousands.
     * The frontend red-tints any row with ratio > 100.
     *
     * COUNT_STAR > 0 guard keeps the NULLIF safe and filters idle rows
     * in the digest table left over from long-past sessions.
     */
    @Select("""
            SELECT DIGEST                                                                AS digest,
                   SUBSTRING(DIGEST_TEXT, 1, 200)                                        AS digest_text,
                   COUNT_STAR                                                            AS count_star,
                   AVG_TIMER_WAIT                                                        AS avg_timer_wait,
                   SUM_TIMER_WAIT                                                        AS sum_timer_wait,
                   SUM_ROWS_EXAMINED                                                     AS sum_rows_examined,
                   SUM_ROWS_SENT                                                         AS sum_rows_sent,
                   ROUND(SUM_ROWS_EXAMINED / NULLIF(SUM_ROWS_SENT, 0), 1)                AS scan_ratio,
                   SUM_NO_INDEX_USED                                                     AS sum_no_index_used,
                   SUM_NO_GOOD_INDEX_USED                                                AS sum_no_good_index_used
              FROM performance_schema.events_statements_summary_by_digest
             WHERE DIGEST IS NOT NULL
               AND COUNT_STAR > 0
             ORDER BY AVG_TIMER_WAIT DESC
             LIMIT 10
            """)
    List<Map<String, Object>> selectSlowDigests();
}
