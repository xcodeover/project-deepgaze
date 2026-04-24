package com.deepgaze.collector.mysql;

import com.deepgaze.collector.Collector;
import com.deepgaze.collector.RatiosSnapshotBuilder;
import com.deepgaze.config.MyBatisFactoryRegistry;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.model.MetricSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * MariaDB collector backed by MyBatis. SQL lives in MariaDbMapper; this class
 * only orchestrates the per-tick SqlSession lifecycle and group-level error
 * handling.
 *
 * Async preservation: MyBatis is synchronous, but collect() is invoked from
 * inside CollectorScheduler#dispatch via CompletableFuture.supplyAsync(...,
 * workers). The mapper calls therefore run on a worker thread — never on the
 * single-thread ticker — so a slow MariaDB cannot block the tick loop or any
 * other target.
 */
@Slf4j
@Component
public class MariaDbCollector implements Collector {

    private final MyBatisFactoryRegistry factories;

    public MariaDbCollector(MyBatisFactoryRegistry factories) {
        this.factories = factories;
    }

    @Override
    public DbType supports() { return DbType.MARIADB; }

    @Override
    public List<MetricSnapshot> collect(DbTargetConfig target, DataSource ds) {
        SqlSessionFactory factory = factories.factoryFor(target.id()).orElse(null);
        if (factory == null) {
            log.warn("MariaDB collect skipped: no SqlSessionFactory for target {}", target.id());
            return List.of();
        }

        List<MetricSnapshot> out = new ArrayList<>(10);

        // openSession(true) → autoCommit=true, matching the read-only Hikari connection
        // and avoiding any need to call session.commit() per tick.
        try (SqlSession session = factory.openSession(true)) {
            MariaDbMapper m = session.getMapper(MariaDbMapper.class);

            // Capture raw rows for status + variables so `dbSaturation` can be
            // derived without re-querying. Both are tiny (<= ~40 rows) so this
            // adds no measurable cost.
            List<Map<String, Object>> statusRows = tryQuery(target, "status",    m::selectStatus);
            List<Map<String, Object>> varRows    = tryQuery(target, "variables", m::selectVariables);
            if (statusRows != null) out.add(snap(target, "status",    statusRows));
            if (varRows    != null) out.add(snap(target, "variables", varRows));

            runOne(target, "sessions",       m::selectSessions).ifPresent(out::add);
            runOne(target, "activeSessions", m::selectActiveSessions).ifPresent(out::add);
            runOne(target, "topWaits",       m::selectTopWaits).ifPresent(out::add);
            runOne(target, "blockers",       m::selectBlockers).ifPresent(out::add);
            runOne(target, "processlist",    m::selectProcesslistTop).ifPresent(out::add);
            runOne(target, "topDigests",     m::selectTopDigests).ifPresent(out::add);
            runOne(target, "slowQueries",    m::selectSlowDigests).ifPresent(out::add);

            // `dbSaturation` — derived KPI. Kept as its own snapshot so both the
            // Engine KPI tile and the `db-session-high` alert rule read the
            // stable direct-column shape {threads_connected, max_connections,
            // session_utilization_pct}.
            if (statusRows != null && varRows != null) {
                MetricSnapshot sat = buildDbSaturation(target, statusRows, varRows);
                if (sat != null) out.add(sat);
            }

            // `ratios` — unified cache/buffer health derived from status rows.
            if (statusRows != null) {
                MetricSnapshot ratios = RatiosSnapshotBuilder.forMariaDb(target, statusRows);
                if (ratios != null) out.add(ratios);
            }
        } catch (Exception e) {
            log.warn("MariaDB session failed: target={} msg={}", target.id(), e.getMessage());
        }

        return out;
    }

    private List<Map<String, Object>> tryQuery(
            DbTargetConfig target,
            String group,
            Supplier<List<Map<String, Object>>> query) {
        try {
            return query.get();
        } catch (Exception e) {
            log.warn("MariaDB query failed: target={} group={} msg={}",
                    target.id(), group, e.getMessage());
            return null;
        }
    }

    private MetricSnapshot snap(DbTargetConfig target, String group, List<Map<String, Object>> rows) {
        return new MetricSnapshot(target.id(), target.displayName(), target.type(), Instant.now(), group, rows);
    }

    private MetricSnapshot buildDbSaturation(
            DbTargetConfig target,
            List<Map<String, Object>> statusRows,
            List<Map<String, Object>> varRows) {
        Long connected = readKv(statusRows, "Threads_connected");
        Long maxConn   = readKv(varRows,    "max_connections");
        if (connected == null || maxConn == null || maxConn <= 0) return null;

        double pct = Math.min(100.0, 100.0 * connected / (double) maxConn);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("threads_connected",       connected);
        row.put("max_connections",         maxConn);
        row.put("session_utilization_pct", Math.round(pct * 10.0) / 10.0);
        return new MetricSnapshot(
                target.id(), target.displayName(), target.type(), Instant.now(), "dbSaturation", List.of(row));
    }

    /**
     * Case-insensitive lookup of a numeric value in a {variable_name, value}
     * row shape. Tolerant of MariaDB returning VARIABLE_VALUE as VARCHAR.
     */
    private static Long readKv(List<Map<String, Object>> rows, String name) {
        for (Map<String, Object> r : rows) {
            Object nm = r.get("variable_name");
            if (nm == null) nm = r.get("Variable_name");
            if (nm == null) continue;
            if (!nm.toString().equalsIgnoreCase(name)) continue;
            Object val = r.get("value");
            if (val == null) val = r.get("Value");
            if (val == null) return null;
            try {
                return (long) Double.parseDouble(val.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Optional<MetricSnapshot> runOne(
            DbTargetConfig target,
            String group,
            Supplier<List<Map<String, Object>>> query) {
        try {
            List<Map<String, Object>> rows = query.get();
            return Optional.of(new MetricSnapshot(
                    target.id(), target.displayName(), target.type(), Instant.now(), group, rows));
        } catch (Exception e) {
            log.warn("MariaDB query failed: target={} group={} msg={}",
                    target.id(), group, e.getMessage());
            return Optional.empty();
        }
    }
}
