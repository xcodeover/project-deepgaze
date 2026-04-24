package com.deepgaze.config;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.targets.TargetRegistry;
import com.deepgaze.targets.event.TargetAddedEvent;
import com.deepgaze.targets.event.TargetRemovedEvent;
import com.deepgaze.targets.event.TargetUpdatedEvent;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle-managed registry of HikariCP pools, one per currently-registered
 * DB target. Pools hot-reload in response to {@link TargetAddedEvent},
 * {@link TargetUpdatedEvent}, {@link TargetRemovedEvent} published by
 * {@link TargetRegistry}, so adding / editing / deleting a target through
 * the REST API takes effect without a restart.
 */
@Slf4j
@Component
@DependsOn({ "targetBootstrapRunner" })
public class DataSourceRegistry {

    private final TargetRegistry targets;
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public DataSourceRegistry(TargetRegistry targets) {
        this.targets = targets;
    }

    @PostConstruct
    public void initPools() {
        for (DbTargetConfig target : targets.all()) {
            buildAndRegister(target);
        }
        if (pools.isEmpty()) {
            log.warn("No DB targets registered at startup. Add one via POST /api/targets.");
        }
    }

    /* ---------- event-driven hot-reload ---------- */

    @EventListener
    public synchronized void onTargetAdded(TargetAddedEvent e) {
        buildAndRegister(e.target());
    }

    @EventListener
    public synchronized void onTargetUpdated(TargetUpdatedEvent e) {
        if (!requiresRebuild(e.previous(), e.current())) {
            log.debug("Target {} updated — cosmetic change only, keeping existing pool.", e.targetId());
            return;
        }
        closePool(e.targetId());
        buildAndRegister(e.current());
    }

    @EventListener
    public synchronized void onTargetRemoved(TargetRemovedEvent e) {
        closePool(e.targetId());
    }

    /* ---------- accessors ---------- */

    public Optional<HikariDataSource> dataSourceFor(String targetId) {
        return Optional.ofNullable(pools.get(targetId));
    }

    public Map<String, HikariDataSource> all() {
        return Collections.unmodifiableMap(pools);
    }

    /* ---------- internals ---------- */

    private void buildAndRegister(DbTargetConfig target) {
        try {
            HikariDataSource ds = HikariPoolFactory.build(target);
            pools.put(target.id(), ds);
            log.info("Initialized HikariCP pool for target id={} type={} url={}",
                    target.id(), target.type(), target.jdbcUrl());
        } catch (Exception e) {
            log.error("Failed to initialize pool for target {}: {}", target.id(), e.toString());
        }
    }

    private void closePool(String id) {
        HikariDataSource ds = pools.remove(id);
        if (ds == null) return;
        try { ds.close(); } catch (Exception ignored) {}
        log.info("Closed HikariCP pool for {}", id);
    }

    /**
     * Any connection-relevant change forces a pool rebuild. Cosmetic fields
     * ({@code displayName}, {@code displayOrder}) do not.
     */
    private static boolean requiresRebuild(DbTargetConfig prev, DbTargetConfig curr) {
        if (prev == null || curr == null) return true;
        if (prev.type() != curr.type()) return true;
        if (!eq(prev.jdbcUrl(), curr.jdbcUrl())) return true;
        if (!eq(prev.username(), curr.username())) return true;
        if (!eq(prev.password(), curr.password())) return true;
        if (prev.hikari().maximumPoolSize() != curr.hikari().maximumPoolSize()) return true;
        if (prev.hikari().minimumIdle()     != curr.hikari().minimumIdle())     return true;
        if (prev.network().tcpConnectTimeoutMs() != curr.network().tcpConnectTimeoutMs()) return true;
        if (prev.network().socketReadTimeoutMs() != curr.network().socketReadTimeoutMs()) return true;
        return false;
    }

    private static boolean eq(Object a, Object b) { return a == null ? b == null : a.equals(b); }

    @PreDestroy
    public void shutdown() {
        pools.forEach((id, ds) -> {
            try { ds.close(); } catch (Exception ignored) {}
            log.info("Closed HikariCP pool for {}", id);
        });
        pools.clear();
    }
}
