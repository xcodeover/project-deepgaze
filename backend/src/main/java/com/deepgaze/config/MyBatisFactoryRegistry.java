package com.deepgaze.config;

import com.deepgaze.collector.JdbcUtils;
import com.deepgaze.collector.mssql.MsSqlMapper;
import com.deepgaze.collector.mysql.MariaDbMapper;
import com.deepgaze.collector.oracle.OracleMapper;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.session.SessionDetailMapper;
import com.deepgaze.targets.TargetRegistry;
import com.deepgaze.targets.event.TargetAddedEvent;
import com.deepgaze.targets.event.TargetRemovedEvent;
import com.deepgaze.targets.event.TargetUpdatedEvent;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One SqlSessionFactory per target DataSource — rebuilt on the fly whenever
 * a target is added, removed, or has its pool rebuilt (see
 * {@link DataSourceRegistry}). Cosmetic updates (displayName / displayOrder)
 * are ignored here since they don't affect the factory's Configuration.
 *
 * Ordering note: the factory is rebuilt AFTER {@link DataSourceRegistry}'s
 * event handler — Spring invokes listeners in bean registration order;
 * MyBatisFactoryRegistry depends on DataSourceRegistry, so it's initialised
 * later and therefore receives events after the pool has been rebuilt.
 */
@Slf4j
@Component
@DependsOn({ "targetBootstrapRunner" })
public class MyBatisFactoryRegistry {

    private final DataSourceRegistry dataSources;
    private final DeepGazeProperties props;
    private final TargetRegistry targets;
    private final Map<String, SqlSessionFactory> factories = new ConcurrentHashMap<>();

    public MyBatisFactoryRegistry(DataSourceRegistry dataSources,
                                  DeepGazeProperties props,
                                  TargetRegistry targets) {
        this.dataSources = dataSources;
        this.props = props;
        this.targets = targets;
    }

    @PostConstruct
    public void initFactories() {
        for (DbTargetConfig target : targets.all()) {
            rebuildFactory(target);
        }
    }

    /* ---------- event-driven hot-reload ---------- */

    @EventListener
    public synchronized void onTargetAdded(TargetAddedEvent e) {
        rebuildFactory(e.target());
    }

    @EventListener
    public synchronized void onTargetUpdated(TargetUpdatedEvent e) {
        // Always rebuild — if DataSourceRegistry didn't rebuild the pool, the factory
        // still references the same DataSource, so this is essentially a no-op.
        // If the pool was rebuilt, we need the factory to point at the fresh DS.
        rebuildFactory(e.current());
    }

    @EventListener
    public synchronized void onTargetRemoved(TargetRemovedEvent e) {
        factories.remove(e.targetId());
        log.info("Removed MyBatis factory for target {}", e.targetId());
    }

    /* ---------- accessors ---------- */

    public Optional<SqlSessionFactory> factoryFor(String targetId) {
        return Optional.ofNullable(factories.get(targetId));
    }

    /* ---------- internals ---------- */

    private void rebuildFactory(DbTargetConfig target) {
        int queryTimeoutSec = JdbcUtils.toQueryTimeoutSec(props.scheduler().collectionTimeoutMs());
        HikariDataSource ds = dataSources.dataSourceFor(target.id()).orElse(null);
        if (ds == null) {
            log.warn("Skipping MyBatis factory for target {} — no DataSource registered.", target.id());
            factories.remove(target.id());
            return;
        }
        try {
            factories.put(target.id(), buildFactory(target, ds, queryTimeoutSec));
            log.info("MyBatis SqlSessionFactory built for target {} (type={}, queryTimeoutSec={})",
                    target.id(), target.type(), queryTimeoutSec);
        } catch (Exception e) {
            log.error("Failed to build SqlSessionFactory for {}: {}", target.id(), e.toString());
        }
    }

    private SqlSessionFactory buildFactory(DbTargetConfig target, HikariDataSource ds, int queryTimeoutSec) {
        Environment env = new Environment(target.id(), new JdbcTransactionFactory(), ds);

        Configuration cfg = new Configuration(env);
        cfg.setMapUnderscoreToCamelCase(false);
        cfg.setUseColumnLabel(true);
        cfg.setDefaultStatementTimeout(queryTimeoutSec);
        cfg.setLocalCacheScope(LocalCacheScope.STATEMENT);

        registerMappers(cfg, target.type());

        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private void registerMappers(Configuration cfg, DbType type) {
        switch (type) {
            case MARIADB -> {
                cfg.addMapper(MariaDbMapper.class);
                cfg.addMapper(SessionDetailMapper.class);
            }
            case ORACLE -> cfg.addMapper(OracleMapper.class);
            case MSSQL  -> cfg.addMapper(MsSqlMapper.class);
            default -> log.debug("No MyBatis mapper registered for type {} (collector still uses JDBC).", type);
        }
    }

    @PreDestroy
    public void shutdown() {
        factories.clear();
        log.info("MyBatisFactoryRegistry cleared.");
    }
}
