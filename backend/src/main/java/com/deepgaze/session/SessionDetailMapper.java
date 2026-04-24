package com.deepgaze.session;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * On-demand queries for the Session Detail drill-down. Separate from
 * MariaDbMapper on purpose — the collector mapper is exercised 1Hz per
 * target and this one is exercised only on user click.
 *
 * The explain query deliberately does not use a parameter binding: MyBatis
 * cannot parameterise `EXPLAIN <stmt>` because the target statement isn't a
 * value, it's syntax. ${sql} inlines the text — callers must sanitise by
 * pulling the SQL from PROCESSLIST.INFO, which is already authenticated to
 * that session on the target DB.
 */
@Mapper
public interface SessionDetailMapper {

    /**
     * Single-row detail. LEFT JOINs InnoDB transaction info (if the session
     * is inside a trx) and the current performance_schema wait event (if any).
     * Returns zero rows when the session has disappeared between clicks.
     */
    @Select("""
            SELECT p.ID                         AS id,
                   p.USER                       AS user,
                   p.HOST                       AS host,
                   p.DB                         AS db,
                   p.COMMAND                    AS command,
                   p.TIME                       AS time_secs,
                   p.STATE                      AS state,
                   p.INFO                       AS info,
                   t.trx_id                     AS trx_id,
                   t.trx_state                  AS trx_state,
                   t.trx_started                AS trx_started,
                   t.trx_rows_locked            AS trx_rows_locked,
                   t.trx_rows_modified          AS trx_rows_modified,
                   t.trx_isolation_level        AS trx_isolation,
                   w.EVENT_NAME                 AS wait_event,
                   w.TIMER_WAIT                 AS wait_timer,
                   w.OBJECT_SCHEMA              AS wait_object_schema,
                   w.OBJECT_NAME                AS wait_object_name
              FROM information_schema.PROCESSLIST p
              LEFT JOIN information_schema.INNODB_TRX t
                ON t.trx_mysql_thread_id = p.ID
              LEFT JOIN performance_schema.threads th
                ON th.PROCESSLIST_ID = p.ID
              LEFT JOIN performance_schema.events_waits_current w
                ON w.THREAD_ID = th.THREAD_ID
               AND w.END_EVENT_ID IS NULL
             WHERE p.ID = #{pid}
             LIMIT 1
            """)
    Map<String, Object> selectSessionById(@Param("pid") long pid);

    /**
     * Runs EXPLAIN FORMAT=JSON against whatever SQL the caller supplies.
     * Result is a single-row, single-column map whose value is a JSON string;
     * the service parses it before returning. ${sql} is intentional — see
     * class-level doc.
     */
    @Select("EXPLAIN FORMAT=JSON ${sql}")
    Map<String, Object> explainJson(@Param("sql") String sql);
}
