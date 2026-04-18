package com.deepgaze.config;

import com.deepgaze.model.DbTargetConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle-managed registry of HikariCP pools, one per configured DB target.
 * Pools are persistent for the lifetime of the application.
 */
@Slf4j
@Component
public class DataSourceRegistry {

    private final DeepGazeProperties props;
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public DataSourceRegistry(DeepGazeProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void initPools() {
        for (DbTargetConfig target : props.targets()) {
            try {
                HikariDataSource ds = HikariPoolFactory.build(target);
                pools.put(target.id(), ds);
                log.info("Initialized HikariCP pool for target id={} type={} url={}",
                        target.id(), target.type(), target.jdbcUrl());
            } catch (Exception e) {
                log.error("Failed to initialize pool for target {}: {}", target.id(), e.toString());
            }
        }
        if (pools.isEmpty()) {
            log.warn("No DB targets configured. Edit application.yml to add targets under 'deepgaze.targets'.");
        }
    }

    public Optional<HikariDataSource> dataSourceFor(String targetId) {
        return Optional.ofNullable(pools.get(targetId));
    }

    public Map<String, HikariDataSource> all() {
        return Collections.unmodifiableMap(pools);
    }

    @PreDestroy
    public void shutdown() {
        pools.forEach((id, ds) -> {
            try { ds.close(); } catch (Exception ignored) {}
            log.info("Closed HikariCP pool for {}", id);
        });
        pools.clear();
    }
}
