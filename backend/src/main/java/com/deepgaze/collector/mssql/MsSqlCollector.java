package com.deepgaze.collector.mssql;

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
 * SQL Server metric collection — uses only DMVs, all of which are in-memory.
 *
 * Hybrid collection strategy: the engine-native perfCounters group is emitted
 * via raw JDBC (its shape is consumed directly by the generic counter tiles),
 * while the five unified drill-down groups ({@code processlist}, {@code blockers},
 * {@code activeSessions}, {@code topDigests}, {@code slowQueries}) go through
 * {@link MsSqlMapper} so their column contracts match MariaDB's exactly.
 */
@Slf4j
@Component
public class MsSqlCollector implements Collector {

    private final int queryTimeoutSec;
    private final MyBatisFactoryRegistry factories;

    public MsSqlCollector(DeepGazeProperties props, MyBatisFactoryRegistry factories) {
        this.queryTimeoutSec = JdbcUtils.toQueryTimeoutSec(props.scheduler().collectionTimeoutMs());
        this.factories = factories;
    }

    @Override
    public DbType supports() { return DbType.MSSQL; }

    /** Whitelisted in-memory perf counters. Engine-native shape. */
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
               'Buffer cache hit ratio base',
               'Lock Waits/sec',
               'Processes blocked',
               'Logins/sec',
               'Logouts/sec'
             )
            """;

    @Override
    public List<MetricSnapshot> collect(DbTargetConfig target, DataSource ds) throws SQLException {
        List<MetricSnapshot> out = new ArrayList<>(6);

        // Raw-JDBC group: engine-native counter shape.
        try (Connection c = ds.getConnection()) {
            Optional<MetricSnapshot> perf = runJdbc(target, c, "perfCounters", PERF_COUNTERS_SQL);
            perf.ifPresent(out::add);

            // `ratios` — unified cache/buffer health derived from the perf
            // counters we just queried (PLE, Buffer Cache Hit Ratio).
            perf.map(s -> RatiosSnapshotBuilder.forMsSql(target, s.rows()))
                .ifPresent(r -> { if (r != null) out.add(r); });
        } catch (SQLException e) {
            log.warn("MSSQL connection acquisition failed: target={} state={} code={} msg={}",
                    target.id(), e.getSQLState(), e.getErrorCode(), e.getMessage());
            throw e;
        }

        // Mapper-backed groups: unified drill-down shape that must match
        // MariaDB's column contract so the Processlist, Lock-Tree, ASH and
        // Query-Performance tiles render identically across engines.
        SqlSessionFactory factory = factories.factoryFor(target.id()).orElse(null);
        if (factory == null) {
            log.warn("MSSQL unified groups skipped: no SqlSessionFactory for target {}", target.id());
            return out;
        }
        try (SqlSession session = factory.openSession(true)) {
            MsSqlMapper m = session.getMapper(MsSqlMapper.class);
            runMapper(target, "processlist",    m::selectProcesslistTop).ifPresent(out::add);
            runMapper(target, "blockers",       m::selectBlockers).ifPresent(out::add);
            runMapper(target, "activeSessions", m::selectActiveSessions).ifPresent(out::add);
            runMapper(target, "topWaits",       m::selectTopWaits).ifPresent(out::add);
            runMapper(target, "topDigests",     m::selectTopDigests).ifPresent(out::add);
            runMapper(target, "slowQueries",    m::selectSlowQueries).ifPresent(out::add);
            runMapper(target, "dbSaturation",   m::selectDbSaturation).ifPresent(out::add);
        } catch (Exception e) {
            log.warn("MSSQL mapper session failed: target={} msg={}", target.id(), e.getMessage());
        }

        return out;
    }

    private Optional<MetricSnapshot> runJdbc(DbTargetConfig target, Connection c, String group, String sql) {
        try {
            return Optional.of(JdbcUtils.runQuery(target, c, queryTimeoutSec, group, sql));
        } catch (SQLException e) {
            log.warn("MSSQL query failed: target={} group={} state={} code={} msg={}",
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
            log.warn("MSSQL mapper query failed: target={} group={} msg={}",
                    target.id(), group, e.getMessage());
            return Optional.empty();
        }
    }
}
