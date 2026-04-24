package com.deepgaze.collector.slow;

import com.deepgaze.config.DataSourceRegistry;
import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.StorageSaturationDto;
import com.deepgaze.targets.TargetRegistry;
import com.deepgaze.targets.event.TargetAddedEvent;
import com.deepgaze.targets.event.TargetRemovedEvent;
import com.deepgaze.targets.event.TargetUpdatedEvent;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 60s cadence scheduler — parallel to {@link com.deepgaze.collector.CollectorScheduler}
 * but dedicated to the expensive dictionary queries behind Storage Saturation.
 * Thread pools are fully isolated so a hung tablespace query on one target
 * cannot slow the 1s infra-metric loop or contend for its worker budget.
 *
 * Each scheduled tick is offset by a small random jitter so fleet-wide
 * fan-out doesn't synchronise every 60s — otherwise N targets would stampede
 * the shared per-target Hikari pools at exactly the same wall-clock instant.
 *
 * Results land in {@link SaturationStore} (last-wins cache); the REST
 * endpoint reads from that cache so a slow backend never blocks API latency.
 */
@Slf4j
@Component
public class SlowCollectorScheduler {

    private final DeepGazeProperties props;
    private final DataSourceRegistry dataSources;
    private final SlowCollectorRegistry collectors;
    private final SaturationStore store;
    private final TargetRegistry targets;

    private ScheduledExecutorService ticker;
    private ExecutorService workers;
    private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    public SlowCollectorScheduler(DeepGazeProperties props,
                                  DataSourceRegistry dataSources,
                                  SlowCollectorRegistry collectors,
                                  SaturationStore store,
                                  TargetRegistry targets) {
        this.props = props;
        this.dataSources = dataSources;
        this.collectors = collectors;
        this.store = store;
        this.targets = targets;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        this.ticker  = Executors.newSingleThreadScheduledExecutor(named("dg-slow-tick"));
        this.workers = Executors.newFixedThreadPool(
                props.scheduler().slowWorkerPoolSize(), named("dg-slow-worker"));

        for (DbTargetConfig target : targets.all()) {
            schedule(target);
        }
    }

    /* ---------- event-driven hot-reload ---------- */

    @EventListener
    public synchronized void onTargetAdded(TargetAddedEvent e) {
        if (ticker == null) return;
        schedule(e.target());
    }

    @EventListener
    public synchronized void onTargetUpdated(TargetUpdatedEvent e) {
        if (ticker == null) return;
        cancel(e.targetId());
        schedule(e.current());
    }

    @EventListener
    public synchronized void onTargetRemoved(TargetRemovedEvent e) {
        cancel(e.targetId());
    }

    /* ---------- internals ---------- */

    private void schedule(DbTargetConfig target) {
        Optional<SlowCollector> collector = collectors.forType(target.type());
        if (collector.isEmpty()) {
            log.info("No SlowCollector for type {} (target={}). Saturation tile will stay empty for this engine.",
                    target.type(), target.id());
            return;
        }
        long interval = props.scheduler().slowIntervalMs();
        // Jitter the first fire so multi-target startups don't all hit their
        // dictionaries simultaneously; subsequent ticks are fixed-rate from
        // that offset.
        long initialDelay = ThreadLocalRandom.current().nextLong(0, Math.max(1L, interval));
        ScheduledFuture<?> f = ticker.scheduleAtFixedRate(
                () -> dispatch(target, collector.get()),
                initialDelay, interval, TimeUnit.MILLISECONDS
        );
        scheduled.put(target.id(), f);
        log.info("Scheduled slow saturation collection for target {} every {}ms (first in {}ms)",
                target.id(), interval, initialDelay);
    }

    private void cancel(String id) {
        ScheduledFuture<?> f = scheduled.remove(id);
        if (f != null) {
            f.cancel(false);
            log.info("Cancelled slow schedule for target {}", id);
        }
    }

    private void dispatch(DbTargetConfig target, SlowCollector collector) {
        Optional<HikariDataSource> dsOpt = dataSources.dataSourceFor(target.id());
        if (dsOpt.isEmpty()) {
            log.debug("Slow tick: no DataSource for target {} (pool rebuilding?); skipping.", target.id());
            return;
        }
        HikariDataSource ds = dsOpt.get();
        long timeout = props.scheduler().slowCollectionTimeoutMs();
        CompletableFuture
                .supplyAsync(() -> safeCollect(target, collector, ds), workers)
                .orTimeout(timeout, TimeUnit.MILLISECONDS)
                .whenComplete((dto, err) -> {
                    if (err != null) {
                        log.warn("Slow saturation collection failed for target={} type={}: {}",
                                target.id(), target.type(), err.toString());
                        return;
                    }
                    if (dto != null) store.put(dto);
                });
    }

    private StorageSaturationDto safeCollect(DbTargetConfig target, SlowCollector collector, HikariDataSource ds) {
        try {
            return collector.collectSaturation(target, ds);
        } catch (Exception e) {
            throw new RuntimeException("slow collect() threw for " + target.id(), e);
        }
    }

    @PreDestroy
    public void stop() {
        scheduled.values().forEach(f -> f.cancel(false));
        scheduled.clear();
        if (ticker  != null) ticker.shutdownNow();
        if (workers != null) workers.shutdownNow();
        log.info("SlowCollectorScheduler stopped.");
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
