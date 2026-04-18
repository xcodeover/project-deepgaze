package com.deepgaze.config;

import com.deepgaze.collector.JdbcUtils;
import com.deepgaze.collector.mysql.MariaDbMapper;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
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
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One SqlSessionFactory per target DataSource, built at startup.
 *
 * Why per-target rather than the starter's single-DataSource model:
 *   - We monitor N independent databases, each with its own HikariCP pool.
 *   - A single SqlSessionFactory can only point at one DataSource.
 *   - Per-target factories also let us register only the mapper(s) relevant
 *     to that engine, keeping each Configuration minimal.
 *
 * The factories are stateless beyond their Configuration; teardown is a no-op
 * because the underlying DataSource lifecycle belongs to DataSourceRegistry.
 */
@Slf4j
@Component
public class MyBatisFactoryRegistry {

    private final DataSourceRegistry dataSources;
    private final DeepGazeProperties props;
    private final Map<String, SqlSessionFactory> factories = new ConcurrentHashMap<>();

    public MyBatisFactoryRegistry(DataSourceRegistry dataSources, DeepGazeProperties props) {
        this.dataSources = dataSources;
        this.props = props;
    }

    @PostConstruct
    public void initFactories() {
        int queryTimeoutSec = JdbcUtils.toQueryTimeoutSec(props.scheduler().collectionTimeoutMs());

        for (DbTargetConfig target : props.targets()) {
            HikariDataSource ds = dataSources.dataSourceFor(target.id()).orElse(null);
            if (ds == null) {
                log.warn("Skipping MyBatis factory for target {} — no DataSource registered.", target.id());
                continue;
            }
            try {
                factories.put(target.id(), buildFactory(target, ds, queryTimeoutSec));
                log.info("MyBatis SqlSessionFactory built for target {} (type={}, queryTimeoutSec={})",
                        target.id(), target.type(), queryTimeoutSec);
            } catch (Exception e) {
                log.error("Failed to build SqlSessionFactory for {}: {}", target.id(), e.toString());
            }
        }
    }

    private SqlSessionFactory buildFactory(DbTargetConfig target, HikariDataSource ds, int queryTimeoutSec) {
        Environment env = new Environment(target.id(), new JdbcTransactionFactory(), ds);

        Configuration cfg = new Configuration(env);
        cfg.setMapUnderscoreToCamelCase(false);          // preserve Variable_name etc. as-is
        cfg.setUseColumnLabel(true);                     // honour SQL column aliases
        cfg.setDefaultStatementTimeout(queryTimeoutSec); // applies to every mapped statement
        cfg.setLocalCacheScope(LocalCacheScope.STATEMENT); // monitoring needs fresh data each tick

        registerMappers(cfg, target.type());

        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private void registerMappers(Configuration cfg, DbType type) {
        switch (type) {
            case MARIADB -> cfg.addMapper(MariaDbMapper.class);
            // Future: case MYSQL -> cfg.addMapper(MySqlMapper.class);
            //         case ORACLE -> cfg.addMapper(OracleMapper.class);
            //         case MSSQL -> cfg.addMapper(MsSqlMapper.class);
            default -> log.debug("No MyBatis mapper registered for type {} (collector still uses JDBC).", type);
        }
    }

    public Optional<SqlSessionFactory> factoryFor(String targetId) {
        return Optional.ofNullable(factories.get(targetId));
    }

    @PreDestroy
    public void shutdown() {
        factories.clear();
        log.info("MyBatisFactoryRegistry cleared.");
    }
}
