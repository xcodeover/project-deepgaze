package com.deepgaze.collector.mssql;

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
 * SQL Server metric collection — uses only DMVs, all of which are in-memory.
 * The CROSS APPLY to sys.dm_exec_sql_text is a table-valued function call
 * (not a heavy join) and is the canonical lightweight way to attach SQL text
 * to query stats; bounded to TOP 10 keeps it cheap.
 */
@Slf4j
@Component
public class MsSqlCollector implements Collector {

    private final int queryTimeoutSec;

    public MsSqlCollector(DeepGazeProperties props) {
        this.queryTimeoutSec = JdbcUtils.toQueryTimeoutSec(props.scheduler().collectionTimeoutMs());
    }

    @Override
    public DbType supports() { return DbType.MSSQL; }

    /** Whitelisted in-memory perf counters. */
    private static final String PERF_COUNTERS_SQL = """
            SELECT RTRIM(object_name)    AS object_name,
                   RTRIM(counter_name)   AS counter_name,
                   RTRIM(instance_name)  AS instance_name,
                   cntr_value,
                   cntr_type
              FROM sys.dm_os_performance_counters
             WHERE counter_name IN (
               'Batch Requests/sec',
               'SQL Compilations/sec',
               'SQL Re-Compilations/sec',
               'User Connections',
               'Active Transactions',
               'Page life expectancy',
               'Buffer cache hit ratio',
               'Lock Waits/sec',
               'Processes blocked',
               'Logins/sec',
               'Logouts/sec'
             )
            """;

    /** Active user requests (session_id > 50 skips system sessions). */
    private static final String REQUESTS_SQL = """
            SELECT session_id,
                   request_id,
                   start_time,
                   status,
                   command,
                   blocking_session_id,
                   wait_type,
                   wait_time,
                   cpu_time,
                   total_elapsed_time,
                   reads,
                   writes,
                   logical_reads
              FROM sys.dm_exec_requests
             WHERE session_id > 50
            """;

    /** User sessions only. */
    private static final String SESSIONS_SQL = """
            SELECT session_id,
                   login_name,
                   host_name,
                   program_name,
                   status,
                   cpu_time,
                   memory_usage,
                   total_elapsed_time,
                   last_request_start_time
              FROM sys.dm_exec_sessions
             WHERE is_user_process = 1
            """;

    /**
     * Top 10 by cumulative elapsed time, with SQL text via CROSS APPLY.
     * The SUBSTRING math extracts only the executed statement (not the whole batch).
     */
    private static final String TOP_SQL = """
            SELECT TOP 10
                   qs.execution_count,
                   qs.total_worker_time,
                   qs.total_elapsed_time,
                   qs.total_logical_reads,
                   qs.last_execution_time,
                   SUBSTRING(st.text,
                             (qs.statement_start_offset / 2) + 1,
                             ((CASE qs.statement_end_offset
                                 WHEN -1 THEN DATALENGTH(st.text)
                                 ELSE qs.statement_end_offset
                               END - qs.statement_start_offset) / 2) + 1
                   ) AS sql_text
              FROM sys.dm_exec_query_stats qs
              CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
             ORDER BY qs.total_elapsed_time DESC
            """;

    @Override
    public List<MetricSnapshot> collect(DbTargetConfig target, DataSource ds) throws SQLException {
        List<MetricSnapshot> out = new ArrayList<>(4);
        try (Connection c = ds.getConnection()) {
            runOne(target, c, "perfCounters", PERF_COUNTERS_SQL).ifPresent(out::add);
            runOne(target, c, "requests",     REQUESTS_SQL).ifPresent(out::add);
            runOne(target, c, "sessions",     SESSIONS_SQL).ifPresent(out::add);
            runOne(target, c, "topSql",       TOP_SQL).ifPresent(out::add);
        } catch (SQLException e) {
            log.warn("MSSQL connection acquisition failed: target={} state={} code={} msg={}",
                    target.id(), e.getSQLState(), e.getErrorCode(), e.getMessage());
            throw e;
        }
        return out;
    }

    private Optional<MetricSnapshot> runOne(DbTargetConfig target, Connection c, String group, String sql) {
        try {
            return Optional.of(JdbcUtils.runQuery(target, c, queryTimeoutSec, group, sql));
        } catch (SQLException e) {
            log.warn("MSSQL query failed: target={} group={} state={} code={} msg={}",
                    target.id(), group, e.getSQLState(), e.getErrorCode(), e.getMessage());
            return Optional.empty();
        }
    }
}
