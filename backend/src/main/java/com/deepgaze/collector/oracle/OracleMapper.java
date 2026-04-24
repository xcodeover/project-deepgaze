package com.deepgaze.collector.oracle;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * Oracle dictionary → unified-DTO mapper. Every method must return rows in
 * the same column-name / type shape that MariaDbMapper produces for the
 * same metric group, so the "dumb" frontend tiles render uniformly across
 * engines.
 *
 * Column aliases are ALL double-quoted to force lowercase labels out of
 * Oracle's JDBC driver (unquoted aliases come back uppercase, which would
 * break the frontend's {@code row.id} / {@code row.user} accessors).
 *
 * Privilege envelope: the {@code deepgaze_monitor} user has
 * {@code CREATE SESSION} + {@code SELECT ANY DICTIONARY}. That covers every
 * {@code v$} / {@code v_$} view referenced here (SELECT ANY DICTIONARY
 * grants access to the underlying {@code v_$} bases that the public
 * {@code v$} synonyms point at). No ops-level rights are required for
 * monitoring.
 *
 * Statement timeout is set globally via MyBatisFactoryRegistry; no
 * per-statement @Options timeout is needed here.
 */
@Mapper
public interface OracleMapper {

    /**
     * Active user sessions in the unified {@code processlist} shape:
     * {@code id, user, host, db, command, time_secs, state, info}.
     *
     * Mapping choices:
     * <ul>
     *   <li>{@code id}         → {@code SID} (single-instance; would need INST_ID for RAC).</li>
     *   <li>{@code command}    → {@code STATUS} ('ACTIVE' / 'INACTIVE' / 'KILLED') — the
     *       closest semantic match for MariaDB's per-session COMMAND column.</li>
     *   <li>{@code state}      → {@code EVENT} (current wait event) or 'ON CPU' when
     *       the session is not waiting — mirrors MariaDB's State column.</li>
     *   <li>{@code time_secs}  → {@code LAST_CALL_ET} (seconds since current status
     *       began) — matches MariaDB's TIME semantics.</li>
     *   <li>{@code info}       → first 200 chars of the current {@code v$sql} text,
     *       joined by (SQL_ID, CHILD_NUMBER). LEFT JOIN so idle sessions don't drop out.</li>
     * </ul>
     *
     * Filters: {@code TYPE='USER'} excludes background / shared-server workers;
     * {@code STATUS='ACTIVE'} mirrors MariaDB's {@code COMMAND <> 'Sleep'};
     * the monitor's own session is excluded so we don't list ourselves.
     */
    @Select("""
            SELECT s.SID                                AS "id",
                   s.USERNAME                           AS "user",
                   s.MACHINE                            AS "host",
                   s.SERVICE_NAME                       AS "db",
                   s.STATUS                             AS "command",
                   s.LAST_CALL_ET                       AS "time_secs",
                   NVL(s.EVENT, 'ON CPU')               AS "state",
                   SUBSTR(sq.SQL_TEXT, 1, 200)          AS "info"
              FROM v$session s
              LEFT JOIN v$sql sq
                ON sq.SQL_ID       = s.SQL_ID
               AND sq.CHILD_NUMBER = s.SQL_CHILD_NUMBER
             WHERE s.TYPE   = 'USER'
               AND s.STATUS = 'ACTIVE'
               AND s.SID    <> SYS_CONTEXT('USERENV', 'SID')
             ORDER BY s.LAST_CALL_ET DESC
             FETCH FIRST 25 ROWS ONLY
            """)
    List<Map<String, Object>> selectProcesslistTop();

    /**
     * Blocker/waiter pairs in the unified {@code blockers} shape:
     * {@code waiting_trx, waiting_process_id, waiting_user, waiting_host,
     *        waiting_secs, waiting_query, blocking_trx, blocking_process_id,
     *        blocking_user, blocking_host, blocking_secs, blocking_query}.
     *
     * Sourced from {@code v$session} self-joined via {@code BLOCKING_SESSION}.
     * This is the supported, license-free path (GV$LOCK / DBA_BLOCKERS would
     * work too but require more joins for identical information). The
     * {@code BLOCKING_SESSION_STATUS = 'VALID'} filter excludes transient
     * 'NO HOLDER' / 'UNKNOWN' cases where the session is waiting but the
     * blocker hasn't been identified yet.
     *
     * Transaction IDs come from {@code v$transaction} via {@code TADDR} —
     * wrapped in {@code RAWTOHEX} so the RAW(8) XID serialises to a JSON
     * string rather than a byte[]. LEFT JOIN so auto-commit single-statement
     * waits (no open transaction) still produce a row.
     *
     * Returns zero rows in the common "no blocking" case.
     */
    @Select("""
            SELECT RAWTOHEX(vt_w.XID)                   AS "waiting_trx",
                   w.SID                                AS "waiting_process_id",
                   w.USERNAME                           AS "waiting_user",
                   w.MACHINE                            AS "waiting_host",
                   w.SECONDS_IN_WAIT                    AS "waiting_secs",
                   SUBSTR(w_sql.SQL_TEXT, 1, 160)       AS "waiting_query",
                   RAWTOHEX(vt_b.XID)                   AS "blocking_trx",
                   b.SID                                AS "blocking_process_id",
                   b.USERNAME                           AS "blocking_user",
                   b.MACHINE                            AS "blocking_host",
                   b.LAST_CALL_ET                       AS "blocking_secs",
                   SUBSTR(b_sql.SQL_TEXT, 1, 160)       AS "blocking_query"
              FROM v$session w
              JOIN v$session b
                ON b.SID = w.BLOCKING_SESSION
              LEFT JOIN v$transaction vt_w ON vt_w.ADDR = w.TADDR
              LEFT JOIN v$transaction vt_b ON vt_b.ADDR = b.TADDR
              LEFT JOIN v$sql w_sql
                ON w_sql.SQL_ID       = w.SQL_ID
               AND w_sql.CHILD_NUMBER = w.SQL_CHILD_NUMBER
              LEFT JOIN v$sql b_sql
                ON b_sql.SQL_ID       = b.SQL_ID
               AND b_sql.CHILD_NUMBER = b.SQL_CHILD_NUMBER
             WHERE w.BLOCKING_SESSION        IS NOT NULL
               AND w.BLOCKING_SESSION_STATUS = 'VALID'
             FETCH FIRST 100 ROWS ONLY
            """)
    List<Map<String, Object>> selectBlockers();

    /**
     * ASH-style snapshot bucketed by wait class, in the unified
     * {@code activeSessions} shape: {@code status, cnt}. Mirrors
     * MariaDB's {@code selectActiveSessions}: sessions currently on CPU
     * land in the 'CPU' bucket; sessions with {@code state='WAITING'}
     * are grouped by {@code wait_class} (User I/O, Concurrency, Network,
     * Commit, etc.).
     *
     * Filters match {@code selectProcesslistTop} so the two tiles describe
     * the same population: user sessions, active status, monitor excluded.
     */
    @Select("""
            SELECT CASE
                     WHEN s.STATE = 'WAITING' THEN NVL(s.WAIT_CLASS, 'Other')
                     ELSE 'CPU'
                   END                                  AS "status",
                   COUNT(*)                             AS "cnt"
              FROM v$session s
             WHERE s.TYPE   = 'USER'
               AND s.STATUS = 'ACTIVE'
               AND s.SID    <> SYS_CONTEXT('USERENV', 'SID')
             GROUP BY CASE
                        WHEN s.STATE = 'WAITING' THEN NVL(s.WAIT_CLASS, 'Other')
                        ELSE 'CPU'
                      END
            """)
    List<Map<String, Object>> selectActiveSessions();

    /**
     * Top 10 SQL digests by cumulative elapsed time — the "By Wait" tab of
     * the Query Performance tile.
     *
     * Aggregated from {@code v$sql} by {@code SQL_ID} (summing across child
     * cursors) rather than {@code v$sqlstats} so the query set stays inside
     * the privilege envelope that already serves {@code selectProcesslistTop}.
     *
     * Unit conversion: Oracle {@code ELAPSED_TIME} is microseconds; MariaDB's
     * {@code SUM_TIMER_WAIT} is picoseconds. The frontend's {@code psToMs}
     * formatter divides by 1e9 to get ms, so we multiply by 1_000_000 to
     * stay on the same scale. Precision loss beyond 2^53 is acceptable for
     * display (ms granularity).
     *
     * Monitor-self exclusion: {@code PARSING_SCHEMA_NAME} filter keeps the
     * collector's own dictionary queries (which dominate on an idle PDB)
     * out of the digest list. Parity note — MariaDB's mapper has no such
     * filter because performance_schema doesn't track caller schema;
     * Oracle does, so we take advantage.
     */
    @Select("""
            SELECT *
              FROM (
                SELECT SQL_ID                                        AS "digest",
                       MIN(SUBSTR(SQL_TEXT, 1, 200))                 AS "digest_text",
                       SUM(EXECUTIONS)                               AS "count_star",
                       SUM(ELAPSED_TIME) * 1000000                   AS "sum_timer_wait",
                       CASE WHEN NVL(SUM(EXECUTIONS), 0) = 0 THEN 0
                            ELSE SUM(ELAPSED_TIME) * 1000000 / SUM(EXECUTIONS)
                       END                                           AS "avg_timer_wait",
                       SUM(BUFFER_GETS)                              AS "sum_rows_examined",
                       SUM(ROWS_PROCESSED)                           AS "sum_rows_sent"
                  FROM v$sql
                 WHERE SQL_ID               IS NOT NULL
                   AND PARSING_SCHEMA_NAME <> 'DEEPGAZE_MONITOR'
                 GROUP BY SQL_ID
                 ORDER BY SUM(ELAPSED_TIME) DESC
              )
             WHERE ROWNUM <= 10
            """)
    List<Map<String, Object>> selectTopDigests();

    /**
     * Top 10 digests by average elapsed time per execution — the
     * "Slow Queries" tab. A rare-but-awful query surfaces here even when
     * dwarfed by high-frequency fast ones in {@link #selectTopDigests}.
     *
     * {@code scan_ratio} is approximated as {@code BUFFER_GETS / ROWS_PROCESSED}
     * — buffer-gets-per-row-returned is Oracle's analogue of MariaDB's
     * {@code rows_examined / rows_sent}. Indexed lookups trend ≤ 2; full
     * scans balloon to hundreds. The frontend red-tints rows above 100.
     *
     * {@code sum_no_index_used} and {@code sum_no_good_index_used} are
     * emitted as 0 — Oracle doesn't track these counters at the cursor
     * level; the columns exist only to keep the DTO shape identical to
     * MariaDB's {@code selectSlowDigests}.
     */
    @Select("""
            SELECT *
              FROM (
                SELECT SQL_ID                                        AS "digest",
                       MIN(SUBSTR(SQL_TEXT, 1, 200))                 AS "digest_text",
                       SUM(EXECUTIONS)                               AS "count_star",
                       SUM(ELAPSED_TIME) * 1000000 / SUM(EXECUTIONS) AS "avg_timer_wait",
                       SUM(ELAPSED_TIME) * 1000000                   AS "sum_timer_wait",
                       SUM(BUFFER_GETS)                              AS "sum_rows_examined",
                       SUM(ROWS_PROCESSED)                           AS "sum_rows_sent",
                       CASE WHEN NVL(SUM(ROWS_PROCESSED), 0) = 0 THEN 0
                            ELSE ROUND(SUM(BUFFER_GETS) / SUM(ROWS_PROCESSED), 1)
                       END                                           AS "scan_ratio",
                       0                                             AS "sum_no_index_used",
                       0                                             AS "sum_no_good_index_used"
                  FROM v$sql
                 WHERE SQL_ID               IS NOT NULL
                   AND PARSING_SCHEMA_NAME <> 'DEEPGAZE_MONITOR'
                   AND EXECUTIONS           > 0
                 GROUP BY SQL_ID
                HAVING SUM(EXECUTIONS) > 0
                 ORDER BY SUM(ELAPSED_TIME) / SUM(EXECUTIONS) DESC
              )
             WHERE ROWNUM <= 10
            """)
    List<Map<String, Object>> selectSlowQueries();

    /**
     * Top wait events in the unified {@code topWaits} shape:
     * {@code event_name, count_star, sum_timer_wait, avg_timer_wait}.
     *
     * Sourced from {@code v$system_event} (cumulative since instance start);
     * TopWaitsChart computes the per-tick delta on the frontend.
     *
     * Unit conversion: Oracle's {@code TIME_WAITED_MICRO} is microseconds.
     * MariaDB's performance_schema emits picoseconds (10^-12 s) and the
     * frontend {@code psToMs} formatter divides by 1e9. Multiplying by 1e6
     * here keeps the three engines on the same scale. {@code avg} is derived
     * from sum/count so there is no separate unit dance on AVERAGE_WAIT
     * (which Oracle stores as centiseconds — awkward and lossy).
     *
     * Idle waits ({@code WAIT_CLASS = 'Idle'}, e.g. "SQL*Net message from
     * client") are filtered out — they dominate TIME_WAITED on any idle
     * instance but reflect session wait-for-client, not server work.
     */
    @Select("""
            SELECT *
              FROM (
                SELECT EVENT                                             AS "event_name",
                       TOTAL_WAITS                                       AS "count_star",
                       TIME_WAITED_MICRO * 1000000                       AS "sum_timer_wait",
                       CASE WHEN TOTAL_WAITS = 0 THEN 0
                            ELSE TIME_WAITED_MICRO * 1000000 / TOTAL_WAITS
                       END                                               AS "avg_timer_wait"
                  FROM v$system_event
                 WHERE WAIT_CLASS <> 'Idle'
                   AND TOTAL_WAITS > 0
                 ORDER BY TIME_WAITED_MICRO DESC
              )
             WHERE ROWNUM <= 20
            """)
    List<Map<String, Object>> selectTopWaits();

    /**
     * Session saturation snapshot in the unified {@code dbSaturation} shape:
     * {@code threads_connected, max_connections, session_utilization_pct,
     *        threads_running}.
     *
     * Source choice — {@code v$parameter('sessions')} + a {@code v$session}
     * headcount. {@code v$resource_limit} would have been the "proper" API
     * but in Oracle XE 21c PDBs the {@code sessions} row is scoped to
     * CDB$ROOT and comes back empty from the pluggable — so the tile would
     * skeleton forever. {@code v$parameter} is populated at every container
     * level, which is the invariant we need.
     *
     * {@code v$parameter.VALUE} is declared VARCHAR2 and can legitimately hold
     * non-numeric sentinels; the {@code REGEXP_LIKE} guard returns NULL for
     * those so the frontend's {@code num()} helper falls back cleanly (the
     * tile renders "—" instead of NaN%). Counts are split so we report both
     * the total connected user session count ({@code threads_connected}) and
     * the currently-active subset ({@code threads_running}) — matching
     * MariaDB's {@code Threads_connected} / {@code Threads_running} pair.
     *
     * The FROM clause uses {@code dual} with two scalar subqueries; v$parameter
     * is always expected to have a 'sessions' row, but {@code MIN()} coalesces
     * a missing row to NULL rather than collapsing the whole result set.
     */
    @Select("""
            SELECT connected                                                   AS "threads_connected",
                   CASE WHEN max_val IS NULL OR max_val = 0 THEN NULL
                        ELSE max_val END                                        AS "max_connections",
                   CASE WHEN max_val IS NULL OR max_val = 0 THEN 0
                        ELSE ROUND(100 * connected / max_val, 1) END            AS "session_utilization_pct",
                   active                                                       AS "threads_running"
              FROM (
                SELECT (SELECT COUNT(*)
                          FROM v$session
                         WHERE TYPE = 'USER'
                           AND USERNAME IS NOT NULL)                             AS connected,
                       (SELECT COUNT(*)
                          FROM v$session
                         WHERE STATUS = 'ACTIVE'
                           AND TYPE   = 'USER')                                  AS active,
                       (SELECT CASE WHEN REGEXP_LIKE(MAX(VALUE), '^[0-9]+$')
                                    THEN TO_NUMBER(MAX(VALUE))
                                    ELSE NULL
                               END
                          FROM v$parameter
                         WHERE NAME = 'sessions')                                AS max_val
                  FROM dual
              )
            """)
    List<Map<String, Object>> selectDbSaturation();
}
