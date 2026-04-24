package com.deepgaze.ops;

import com.deepgaze.config.HikariPoolFactory;
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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second, intentionally-separate pool registry for active-response (Kill)
 * commands. Holds a write-capable pool ONLY when the target has ops
 * credentials configured; a target without ops creds returns empty from
 * {@link #dataSourceFor} and the controller translates that into a 503.
 *
 * Hot-reloads on the same {@link TargetRegistry} events as
 * {@link com.deepgaze.config.DataSourceRegistry}: add / remove / update
 * the ops pool as targets come and go.
 */
@Slf4j
@Component
@DependsOn({ "targetBootstrapRunner" })
public class OpsDataSourceRegistry {

    private final TargetRegistry targets;
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public OpsDataSourceRegistry(TargetRegistry targets) {
        this.targets = targets;
    }

    @PostConstruct
    public void initPools() {
        for (DbTargetConfig target : targets.all()) {
            if (target.hasOps()) buildAndRegister(target);
            else log.info("Ops pool for target id={} not configured — Kill Session disabled for this target.",
                    target.id());
        }
    }

    /* ---------- event-driven hot-reload ---------- */

    @EventListener
    public synchronized void onTargetAdded(TargetAddedEvent e) {
        if (e.target().hasOps()) buildAndRegister(e.target());
    }

    @EventListener
    public synchronized void onTargetUpdated(TargetUpdatedEvent e) {
        closePool(e.targetId());
        if (e.current().hasOps()) buildAndRegister(e.current());
    }

    @EventListener
    public synchronized void onTargetRemoved(TargetRemovedEvent e) {
        closePool(e.targetId());
    }

    /* ---------- accessors ---------- */

    public Optional<HikariDataSource> dataSourceFor(String targetId) {
        return Optional.ofNullable(pools.get(targetId));
    }

    /* ---------- internals ---------- */

    private void buildAndRegister(DbTargetConfig target) {
        try {
            HikariDataSource ds = HikariPoolFactory.buildOps(target);
            pools.put(target.id(), ds);
            log.info("Initialized ops HikariCP pool for target id={} user={}",
                    target.id(), target.ops().username());
        } catch (Exception e) {
            log.error("Failed to initialize ops pool for target {}: {}", target.id(), e.toString());
        }
    }

    private void closePool(String id) {
        HikariDataSource ds = pools.remove(id);
        if (ds == null) return;
        try { ds.close(); } catch (Exception ignored) {}
        log.info("Closed ops HikariCP pool for {}", id);
    }

    @PreDestroy
    public void shutdown() {
        pools.forEach((id, ds) -> {
            try { ds.close(); } catch (Exception ignored) {}
            log.info("Closed ops HikariCP pool for {}", id);
        });
        pools.clear();
    }
}
