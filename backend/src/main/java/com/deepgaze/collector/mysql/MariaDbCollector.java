package com.deepgaze.collector.mysql;

import com.deepgaze.collector.Collector;
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

        List<MetricSnapshot> out = new ArrayList<>(4);

        // openSession(true) → autoCommit=true, matching the read-only Hikari connection
        // and avoiding any need to call session.commit() per tick.
        try (SqlSession session = factory.openSession(true)) {
            MariaDbMapper m = session.getMapper(MariaDbMapper.class);

            runOne(target, "status",      m::selectStatus).ifPresent(out::add);
            runOne(target, "variables",   m::selectVariables).ifPresent(out::add);
            runOne(target, "processlist", m::selectProcesslist).ifPresent(out::add);
            runOne(target, "topDigests",  m::selectTopDigests).ifPresent(out::add);
        } catch (Exception e) {
            log.warn("MariaDB session failed: target={} msg={}", target.id(), e.getMessage());
        }

        return out;
    }

    private Optional<MetricSnapshot> runOne(
            DbTargetConfig target,
            String group,
            Supplier<List<Map<String, Object>>> query) {
        try {
            List<Map<String, Object>> rows = query.get();
            return Optional.of(new MetricSnapshot(
                    target.id(), target.type(), Instant.now(), group, rows));
        } catch (Exception e) {
            log.warn("MariaDB query failed: target={} group={} msg={}",
                    target.id(), group, e.getMessage());
            return Optional.empty();
        }
    }
}
