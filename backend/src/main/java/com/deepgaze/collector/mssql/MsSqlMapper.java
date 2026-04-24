package com.deepgaze.collector.mssql;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * SQL Server DMV → unified-DTO mapper. Every method returns rows in the
 * same column-name / type shape that {@link com.deepgaze.collector.mysql.MariaDbMapper}
 * produces for the same metric group, so the "dumb" frontend tiles render
 * uniformly across engines.
 *
 * Reserved-word aliases: T-SQL requires {@code [user]} around the keyword
 * USER in the alias list, but the JDBC driver returns the label without
 * brackets — so the JSON key ends up as {@code "user"}, matching MariaDB.
 *
 * Privilege envelope: {@code deepgaze_monitor} has {@code CONNECT SQL},
 * {@code VIEW SERVER STATE}, and {@code VIEW ANY DEFINITION}. All DMVs
 * referenced here are gated on {@code VIEW SERVER STATE}, which is the
 * server-wide grant that covers {@code sys.dm_exec_*}, {@code sys.dm_tran_*},
 * {@code sys.dm_os_*}. No database-level permission is required.
 *
 * Statement timeout is set on the per-target Configuration in
 * {@link com.deepgaze.config.MyBatisFactoryRegistry}, so the @Select methods
 * do not need explicit @Options timeouts.
 */
@Mapper
public interface MsSqlMapper {

    /**
     * Active user requests in the unified {@code processlist} shape:
     * {@code id, user, host, db, command, time_secs, state, info}.
     *
     * Mapping choices:
     * <ul>
     *   <li>{@code id}         → {@code session_id}.</li>
     *   <li>{@code command}    → {@code r.command} ('SELECT', 'INSERT',
     *       'INSERT EXEC', 'BACKUP LOG', …) — the closest semantic match
     *       for MariaDB's COMMAND column which identifies the kind of work.</li>
     *   <li>{@code state}      → {@code wait_type} when waiting, otherwise
     *       {@code r.status} ('running', 'runnable', 'suspended').
     *       Mirrors MariaDB's State column which mixes wait reasons and
     *       execution phases.</li>
     *   <li>{@code time_secs}  → seconds since {@code r.start_time}
     *       (the request's begin), matching MariaDB's TIME column.</li>
     *   <li>{@code info}       → first 200 chars of the batch text via
     *       {@code OUTER APPLY sys.dm_exec_sql_text}. OUTER (not CROSS)
     *       APPLY keeps rows with null sql_handle (rare — e.g., during
     *       compilation).</li>
     * </ul>
     *
     * Filters: {@code s.is_user_process = 1} excludes system sessions
     * (replaces the naive {@code session_id > 50} heuristic that can
     * misclassify high-numbered system SPIDs); {@code @@SPID} self-exclusion
     * hides the collector's own request.
     */
    @Select("""
            SELECT TOP 25
                   r.session_id                                 AS id,
                   s.login_name                                 AS [user],
                   s.host_name                                  AS host,
                   DB_NAME(r.database_id)                       AS db,
                   r.command                                    AS command,
                   DATEDIFF(SECOND, r.start_time, GETDATE())    AS time_secs,
                   COALESCE(r.wait_type, r.status)              AS state,
                   SUBSTRING(t.text, 1, 200)                    AS info
              FROM sys.dm_exec_requests r
              JOIN sys.dm_exec_sessions s ON s.session_id = r.session_id
              OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t
             WHERE s.is_user_process = 1
               AND r.session_id <> @@SPID
             ORDER BY time_secs DESC
            """)
    List<Map<String, Object>> selectProcesslistTop();

    /**
     * Blocker/waiter pairs in the unified {@code blockers} shape.
     *
     * Blocker identification: {@code r.blocking_session_id} on the waiting
     * request — SQL Server's first-class pointer to the blocker. Avoids the
     * heavier {@code sys.dm_os_waiting_tasks} route and is sufficient for
     * the Lock-Tree tile.
     *
     * Transaction IDs come from {@code sys.dm_tran_session_transactions},
     * aggregated with {@code MIN(transaction_id)} per session (a session
     * can host multiple transactions when MARS is in use; the earliest is
     * typically the one holding / requesting the lock). LEFT JOIN so
     * auto-commit single-statement waits (no open txn) still produce a row.
     *
     * Blocker SQL: the blocker is usually <em>idle</em> (between statements
     * with an open txn), so {@code sys.dm_exec_requests} doesn't have a row
     * for it. {@code sys.dm_exec_connections.most_recent_sql_handle} gives
     * the last statement the blocker ran — enough context for an operator
     * to identify what the blocker is holding.
     *
     * {@code blocking_secs} uses {@code s_b.last_request_start_time}
     * (from {@code dm_exec_sessions}) because the blocker has usually
     * finished its last request; this mirrors MariaDB's {@code bp.TIME}
     * (time since last command on the PROCESSLIST row).
     *
     * Returns zero rows in the common "no blocking" case.
     */
    @Select("""
            SELECT CAST(tr_w.transaction_id AS VARCHAR(40))                 AS waiting_trx,
                   r.session_id                                             AS waiting_process_id,
                   s_w.login_name                                           AS waiting_user,
                   s_w.host_name                                            AS waiting_host,
                   DATEDIFF(SECOND, r.start_time, GETDATE())                AS waiting_secs,
                   SUBSTRING(t_w.text, 1, 160)                              AS waiting_query,
                   CAST(tr_b.transaction_id AS VARCHAR(40))                 AS blocking_trx,
                   s_b.session_id                                           AS blocking_process_id,
                   s_b.login_name                                           AS blocking_user,
                   s_b.host_name                                            AS blocking_host,
                   DATEDIFF(SECOND, s_b.last_request_start_time, GETDATE()) AS blocking_secs,
                   SUBSTRING(t_b.text, 1, 160)                              AS blocking_query
              FROM sys.dm_exec_requests r
              JOIN sys.dm_exec_sessions s_w ON s_w.session_id = r.session_id
              JOIN sys.dm_exec_sessions s_b ON s_b.session_id = r.blocking_session_id
              LEFT JOIN sys.dm_exec_connections c_b ON c_b.session_id = s_b.session_id
              OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t_w
              OUTER APPLY sys.dm_exec_sql_text(c_b.most_recent_sql_handle) t_b
              LEFT JOIN (
                  SELECT session_id, MIN(transaction_id) AS transaction_id
                    FROM sys.dm_tran_session_transactions
                   GROUP BY session_id
              ) tr_w ON tr_w.session_id = r.session_id
              LEFT JOIN (
                  SELECT session_id, MIN(transaction_id) AS transaction_id
                    FROM sys.dm_tran_session_transactions
                   GROUP BY session_id
              ) tr_b ON tr_b.session_id = s_b.session_id
             WHERE r.blocking_session_id IS NOT NULL
               AND r.blocking_session_id <> 0
            """)
    List<Map<String, Object>> selectBlockers();

    /**
     * ASH-style snapshot bucketed by wait category, in the unified
     * {@code activeSessions} shape: {@code status, cnt}.
     *
     * Bucket keys mirror what commercial APMs surface (Dynatrace DPM,
     * SolarWinds DPA) so operators see familiar categories. {@code CPU}
     * covers both the NULL-wait case and {@code SOS_SCHEDULER_YIELD}
     * (sessions voluntarily yielding after exhausting their quantum —
     * semantically still CPU-bound).
     *
     * Filters match {@link #selectProcesslistTop} so the two tiles
     * describe the same population: user sessions, active requests,
     * monitor excluded.
     */
    @Select("""
            SELECT status, COUNT(*) AS cnt
              FROM (
                  SELECT CASE
                           WHEN r.wait_type IS NULL                          THEN 'CPU'
                           WHEN r.wait_type = 'SOS_SCHEDULER_YIELD'          THEN 'CPU'
                           WHEN r.wait_type LIKE 'PAGEIOLATCH%'              THEN 'File I/O'
                           WHEN r.wait_type = 'WRITELOG'                     THEN 'Log I/O'
                           WHEN r.wait_type LIKE 'LCK_M_%'                   THEN 'Lock'
                           WHEN r.wait_type LIKE 'PAGELATCH%'
                             OR r.wait_type LIKE 'LATCH_%'                   THEN 'Latch'
                           WHEN r.wait_type IN ('CXPACKET','CXCONSUMER')     THEN 'Parallelism'
                           WHEN r.wait_type = 'ASYNC_NETWORK_IO'             THEN 'Network I/O'
                           WHEN r.wait_type LIKE 'RESOURCE_SEMAPHORE%'       THEN 'Memory'
                           ELSE 'Other'
                         END AS status
                    FROM sys.dm_exec_requests r
                    JOIN sys.dm_exec_sessions s ON s.session_id = r.session_id
                   WHERE s.is_user_process = 1
                     AND r.session_id <> @@SPID
              ) x
             GROUP BY status
            """)
    List<Map<String, Object>> selectActiveSessions();

    /**
     * Top 10 query-hash buckets by cumulative elapsed time — the "By Wait"
     * tab of the Query Performance tile.
     *
     * Aggregated by {@code query_hash}, which is SQL Server's closest
     * analogue to MariaDB's {@code DIGEST}: a literal-independent hash of
     * the statement shape. A single {@code query_hash} can span multiple
     * plan entries (different {@code plan_hash} values), so SUM across the
     * group gives per-statement totals.
     *
     * Unit conversion: {@code total_elapsed_time} is microseconds; the
     * frontend {@code psToMs} formatter divides by 1e9 to get ms, so we
     * multiply by 1_000_000 for picosecond parity. The intermediate is
     * cast to FLOAT to avoid the rare bigint overflow on long-lived
     * servers (bigint × 10^6 can overflow after ~290k years … but also
     * sooner if the DMV isn't cleared; FLOAT stays well-defined).
     *
     * {@code sum_rows_examined} → {@code total_logical_reads} (analogue of
     * Oracle's BUFFER_GETS). {@code sum_rows_sent} → {@code total_rows}
     * (SQL Server 2016+; present on every supported MSSQL version).
     */
    @Select("""
            SELECT TOP 10 *
              FROM (
                  SELECT CONVERT(VARCHAR(34), qs.query_hash, 1)                                       AS digest,
                         MIN(SUBSTRING(st.text, 1, 200))                                              AS digest_text,
                         SUM(qs.execution_count)                                                      AS count_star,
                         CAST(SUM(qs.total_elapsed_time) AS FLOAT) * 1000000                          AS sum_timer_wait,
                         CASE WHEN SUM(qs.execution_count) = 0 THEN 0
                              ELSE CAST(SUM(qs.total_elapsed_time) AS FLOAT) * 1000000
                                 / SUM(qs.execution_count)
                         END                                                                          AS avg_timer_wait,
                         SUM(qs.total_logical_reads)                                                  AS sum_rows_examined,
                         SUM(qs.total_rows)                                                           AS sum_rows_sent
                    FROM sys.dm_exec_query_stats qs
                    CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
                   WHERE qs.query_hash IS NOT NULL
                   GROUP BY qs.query_hash
              ) x
             ORDER BY sum_timer_wait DESC
            """)
    List<Map<String, Object>> selectTopDigests();

    /**
     * Top 10 query-hash buckets by average elapsed per execution — the
     * "Slow Queries" tab. A rare-but-awful query surfaces here even when
     * dwarfed by high-frequency fast ones in {@link #selectTopDigests}.
     *
     * {@code scan_ratio} is approximated as
     * {@code total_logical_reads / total_rows} — logical-reads-per-row-returned
     * is SQL Server's analogue of MariaDB's {@code rows_examined / rows_sent}.
     * Indexed seeks trend ≤ 2; full scans balloon to hundreds. Frontend
     * red-tints rows above 100.
     *
     * {@code sum_no_index_used} and {@code sum_no_good_index_used} emit 0 —
     * {@code dm_exec_query_stats} doesn't track these. Columns exist only
     * to keep the DTO shape identical to MariaDB's {@code selectSlowDigests}.
     */
    @Select("""
            SELECT TOP 10 *
              FROM (
                  SELECT CONVERT(VARCHAR(34), qs.query_hash, 1)                                       AS digest,
                         MIN(SUBSTRING(st.text, 1, 200))                                              AS digest_text,
                         SUM(qs.execution_count)                                                      AS count_star,
                         CAST(SUM(qs.total_elapsed_time) AS FLOAT) * 1000000
                           / NULLIF(SUM(qs.execution_count), 0)                                       AS avg_timer_wait,
                         CAST(SUM(qs.total_elapsed_time) AS FLOAT) * 1000000                          AS sum_timer_wait,
                         SUM(qs.total_logical_reads)                                                  AS sum_rows_examined,
                         SUM(qs.total_rows)                                                           AS sum_rows_sent,
                         CASE WHEN SUM(qs.total_rows) = 0 THEN 0
                              ELSE ROUND(CAST(SUM(qs.total_logical_reads) AS FLOAT)
                                       / SUM(qs.total_rows), 1)
                         END                                                                          AS scan_ratio,
                         0                                                                            AS sum_no_index_used,
                         0                                                                            AS sum_no_good_index_used
                    FROM sys.dm_exec_query_stats qs
                    CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
                   WHERE qs.query_hash IS NOT NULL
                     AND qs.execution_count > 0
                   GROUP BY qs.query_hash
              ) x
             ORDER BY avg_timer_wait DESC
            """)
    List<Map<String, Object>> selectSlowQueries();

    /**
     * Top wait events in the unified {@code topWaits} shape:
     * {@code event_name, count_star, sum_timer_wait, avg_timer_wait}.
     *
     * Sourced from {@code sys.dm_os_wait_stats} (cumulative since server
     * start or last {@code DBCC SQLPERF} clear); TopWaitsChart computes the
     * per-tick delta on the frontend.
     *
     * Unit conversion: {@code wait_time_ms} is milliseconds; MariaDB emits
     * picoseconds (10^-12 s) and the frontend {@code psToMs} formatter
     * divides by 1e9. Multiplying by 1e9 here keeps the three engines on
     * the same scale. Cast to FLOAT first so SUM stays well-defined on
     * long-running servers (bigint × 10^9 can overflow).
     *
     * Benign-wait ignore list: the Paul Randal / Microsoft CSS canonical
     * set of waits that accumulate even on an idle server (background
     * tasks, checkpointers, broker idlers, extended-events dispatcher, …).
     * Filtering them server-side keeps the top-20 list actionable.
     */
    @Select("""
            SELECT TOP 20
                   wait_type                                    AS event_name,
                   waiting_tasks_count                          AS count_star,
                   CAST(wait_time_ms AS FLOAT) * 1000000000     AS sum_timer_wait,
                   CASE WHEN waiting_tasks_count = 0 THEN 0
                        ELSE CAST(wait_time_ms AS FLOAT) * 1000000000
                           / waiting_tasks_count
                   END                                          AS avg_timer_wait
              FROM sys.dm_os_wait_stats
             WHERE waiting_tasks_count > 0
               AND wait_type NOT IN (
                 'BROKER_EVENTHANDLER','BROKER_RECEIVE_WAITFOR','BROKER_TASK_STOP',
                 'BROKER_TO_FLUSH','BROKER_TRANSMITTER','CHECKPOINT_QUEUE',
                 'CLR_AUTO_EVENT','CLR_MANUAL_EVENT','CLR_SEMAPHORE',
                 'DBMIRROR_EVENTS_QUEUE','DBMIRROR_WORKER_QUEUE','DIRTY_PAGE_POLL',
                 'DISPATCHER_QUEUE_SEMAPHORE','FT_IFTS_SCHEDULER_IDLE_WAIT',
                 'FT_IFTSHC_MUTEX','HADR_CLUSAPI_CALL','HADR_FILESTREAM_IOMGR_IOCOMPLETION',
                 'HADR_LOGCAPTURE_WAIT','HADR_NOTIFICATION_DEQUEUE','HADR_TIMER_TASK',
                 'HADR_WORK_QUEUE','KSOURCE_WAKEUP','LAZYWRITER_SLEEP','LOGMGR_QUEUE',
                 'ONDEMAND_TASK_QUEUE','PWAIT_ALL_COMPONENTS_INITIALIZED',
                 'QDS_PERSIST_TASK_MAIN_LOOP_SLEEP','QDS_ASYNC_QUEUE',
                 'REQUEST_FOR_DEADLOCK_SEARCH','RESOURCE_QUEUE','SERVER_IDLE_CHECK',
                 'SLEEP_BPOOL_FLUSH','SLEEP_DBSTARTUP','SLEEP_DCOMSTARTUP',
                 'SLEEP_MASTERDBREADY','SLEEP_MASTERMDREADY','SLEEP_MASTERUPGRADED',
                 'SLEEP_MSDBSTARTUP','SLEEP_SYSTEMTASK','SLEEP_TASK',
                 'SLEEP_TEMPDBSTARTUP','SNI_HTTP_ACCEPT','SP_SERVER_DIAGNOSTICS_SLEEP',
                 'SQLTRACE_BUFFER_FLUSH','SQLTRACE_INCREMENTAL_FLUSH_SLEEP',
                 'SQLTRACE_WAIT_ENTRIES','WAIT_FOR_RESULTS','WAITFOR',
                 'WAITFOR_TASKSHUTDOWN','WAIT_XTP_HOST_WAIT','WAIT_XTP_OFFLINE_CKPT_NEW_LOG',
                 'WAIT_XTP_CKPT_CLOSE','XE_DISPATCHER_JOIN','XE_DISPATCHER_WAIT',
                 'XE_TIMER_EVENT'
               )
             ORDER BY wait_time_ms DESC
            """)
    List<Map<String, Object>> selectTopWaits();

    /**
     * Session saturation snapshot in the unified {@code dbSaturation} shape:
     * {@code threads_connected, max_connections, session_utilization_pct,
     *        threads_running}.
     *
     * {@code @@MAX_CONNECTIONS} returns the configured {@code user connections}
     * cap (0 in SQL Server conventionally means "dynamic" / unlimited by the
     * documented 32,767 ceiling). The CASE guard treats 0 as "unknown cap"
     * and reports 0% utilisation so the tile stays truthful rather than
     * showing an impossible ∞%.
     *
     * {@code threads_running} matches the Processlist filter ({@code is_user_process
     * = 1}) and counts requests actively progressing on a worker (running,
     * runnable, or suspended on a wait).
     */
    @Select("""
            SELECT (SELECT COUNT(*)
                      FROM sys.dm_exec_sessions
                     WHERE is_user_process = 1)                          AS threads_connected,
                   CAST(@@MAX_CONNECTIONS AS INT)                        AS max_connections,
                   CASE WHEN @@MAX_CONNECTIONS = 0 THEN 0
                        ELSE ROUND(100.0 *
                             (SELECT COUNT(*) FROM sys.dm_exec_sessions
                               WHERE is_user_process = 1)
                             / CAST(@@MAX_CONNECTIONS AS FLOAT), 1)
                   END                                                   AS session_utilization_pct,
                   (SELECT COUNT(*)
                      FROM sys.dm_exec_requests r
                      JOIN sys.dm_exec_sessions s ON s.session_id = r.session_id
                     WHERE s.is_user_process = 1
                       AND r.status IN ('running','runnable','suspended'))
                                                                         AS threads_running
            """)
    List<Map<String, Object>> selectDbSaturation();
}
