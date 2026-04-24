package com.deepgaze.collector.oracle;

import com.deepgaze.collector.Collector;
import com.deepgaze.collector.JdbcUtils;
import com.deepgaze.collector.RatiosSnapshotBuilder;
import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.config.MyBatisFactoryRegistry;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.model.MetricSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Oracle metric collection from in-memory dynamic performance views.
 * Avoids v$active_session_history (licensed feature) — uses a per-second snapshot
 * of v$session as a free, lightweight substitute.
 *
 * Hybrid collection strategy: the aggregated engine-KPI groups ({@code sessions},
 * {@code sysstat}, {@code sysmetric}) are emitted via raw JDBC statements
 * defined inline here, while the unified drill-down groups ({@code processlist},
 * {@code blockers}, {@code activeSessions}, {@code topDigests}, {@code slowQueries})
 * that must match MariaDB's exact column contract go through {@link OracleMapper}
 * so the SQL-to-DTO translation lives in one annotated place. Both run under
 * the same per-target Hikari connection budget and statement timeout.
 */
@Slf4j
@Component
public class OracleCollector implements Collector {

    private final int queryTimeoutSec;
    private final MyBatisFactoryRegistry factories;

    public OracleCollector(DeepGazeProperties props, MyBatisFactoryRegistry factories) {
        this.queryTimeoutSec = JdbcUtils.toQueryTimeoutSec(props.scheduler().collectionTimeoutMs());
        this.factories = factories;
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

    @Override
    public List<MetricSnapshot> collect(DbTargetConfig target, DataSource ds) throws SQLException {
        List<MetricSnapshot> out = new ArrayList<>(8);

        // Raw-JDBC groups: aggregated engine KPIs — shape is engine-native and
        // consumed by generic counter/chart tiles.
        try (Connection c = ds.getConnection()) {
            runJdbc(target, c, "sessions",  SESSIONS_SQL).ifPresent(out::add);
            Optional<MetricSnapshot> sysstat = runJdbc(target, c, "sysstat", SYSSTAT_SQL);
            sysstat.ifPresent(out::add);
            runJdbc(target, c, "sysmetric", SYSMETRIC_SQL).ifPresent(out::add);

            // `ratios` — unified cache/buffer health derived from the sysstat
            // counters we just queried (buffer cache hit %, parse hit %).
            sysstat.map(s -> RatiosSnapshotBuilder.forOracle(target, s.rows()))
                   .ifPresent(r -> { if (r != null) out.add(r); });
        } catch (SQLException e) {
            log.warn("Oracle connection acquisition failed: target={} state={} code={} msg={}",
                    target.id(), e.getSQLState(), e.getErrorCode(), e.getMessage());
            throw e;
        }

        // Mapper-backed groups: unified drill-down shape that must match
        // MariaDB's column contract so the Processlist, Lock-Tree, ASH and
        // Query-Performance tiles render identically across engines.
        SqlSessionFactory factory = factories.factoryFor(target.id()).orElse(null);
        if (factory == null) {
            log.warn("Oracle unified groups skipped: no SqlSessionFactory for target {}", target.id());
            return out;
        }
        try (SqlSession session = factory.openSession(true)) {
            OracleMapper m = session.getMapper(OracleMapper.class);
            runMapper(target, "processlist",    m::selectProcesslistTop).ifPresent(out::add);
            runMapper(target, "blockers",       m::selectBlockers).ifPresent(out::add);
            runMapper(target, "activeSessions", m::selectActiveSessions).ifPresent(out::add);
            runMapper(target, "topWaits",       m::selectTopWaits).ifPresent(out::add);
            runMapper(target, "topDigests",     m::selectTopDigests).ifPresent(out::add);
            runMapper(target, "slowQueries",    m::selectSlowQueries).ifPresent(out::add);
            runMapper(target, "dbSaturation",   m::selectDbSaturation).ifPresent(out::add);
        } catch (Exception e) {
            log.warn("Oracle mapper session failed: target={} msg={}", target.id(), e.getMessage());
        }

        return out;
    }

    private Optional<MetricSnapshot> runJdbc(DbTargetConfig target, Connection c, String group, String sql) {
        try {
            return Optional.of(JdbcUtils.runQuery(target, c, queryTimeoutSec, group, sql));
        } catch (SQLException e) {
            log.warn("Oracle query failed: target={} group={} state={} code={} msg={}",
                    target.id(), group, e.getSQLState(), e.getErrorCode(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<MetricSnapshot> runMapper(
            DbTargetConfig target,
            String group,
            Supplier<List<Map<String, Object>>> query) {
        try {
            List<Map<String, Object>> rows = query.get();
            return Optional.of(new MetricSnapshot(
                    target.id(), target.displayName(), target.type(), Instant.now(), group, rows));
        } catch (Exception e) {
            log.warn("Oracle mapper query failed: target={} group={} msg={}",
                    target.id(), group, e.getMessage());
            return Optional.empty();
        }
    }
}
