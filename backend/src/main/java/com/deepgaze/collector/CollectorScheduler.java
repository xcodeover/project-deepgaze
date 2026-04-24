package com.deepgaze.collector;

import com.deepgaze.config.DataSourceRegistry;
import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.MetricSnapshot;
import com.deepgaze.stream.MetricSink;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-target scheduled tick → fire-and-forget submission to a shared worker pool.
 *
 * Targets added/updated/removed at runtime are rescheduled via the same
 * {@link TargetRegistry} event stream that drives the pool registries; a
 * schedule is cancelled and re-created whenever the target's
 * {@code pollIntervalMs} or id changes.
 *
 * Initialization runs on ApplicationReadyEvent (not @PostConstruct) so every
 * subscriber to MetricStream — most importantly the MetricRingBuffer — is
 * registered before the first tick fires, ensuring no startup events are dropped.
 */
@Slf4j
@Component
public class CollectorScheduler {

    private final DeepGazeProperties props;
    private final DataSourceRegistry dataSources;
    private final CollectorRegistry collectors;
    private final MetricSink sink;
    private final TargetRegistry targets;

    private ScheduledExecutorService ticker;
    private ExecutorService workers;
    private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    public CollectorScheduler(DeepGazeProperties props,
                              DataSourceRegistry dataSources,
                              CollectorRegistry collectors,
                              MetricSink sink,
                              TargetRegistry targets) {
        this.props = props;
        this.dataSources = dataSources;
        this.collectors = collectors;
        this.sink = sink;
        this.targets = targets;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        this.ticker  = Executors.newSingleThreadScheduledExecutor(named("dg-tick"));
        this.workers = Executors.newFixedThreadPool(props.scheduler().workerPoolSize(), named("dg-worker"));

        for (DbTargetConfig target : targets.all()) {
            schedule(target);
        }
    }

    /* ---------- event-driven hot-reload ---------- */

    @EventListener
    public synchronized void onTargetAdded(TargetAddedEvent e) {
        if (ticker == null) return; // not started yet; initial start() will pick it up
        schedule(e.target());
    }

    @EventListener
    public synchronized void onTargetUpdated(TargetUpdatedEvent e) {
        if (ticker == null) return;
        // Always cancel + reschedule — pollIntervalMs may have changed, and rescheduling
        // is cheap. The pool reference is resolved fresh on every dispatch tick.
        cancel(e.targetId());
        schedule(e.current());
    }

    @EventListener
    public synchronized void onTargetRemoved(TargetRemovedEvent e) {
        cancel(e.targetId());
    }

    /* ---------- internals ---------- */

    private void schedule(DbTargetConfig target) {
        Optional<Collector> collector = collectors.forType(target.type());
        if (collector.isEmpty()) {
            log.warn("No Collector registered for type {} (target={}). Skipping schedule.",
                    target.type(), target.id());
            return;
        }
        ScheduledFuture<?> f = ticker.scheduleAtFixedRate(
                () -> dispatch(target, collector.get()),
                0L, target.pollIntervalMs(), TimeUnit.MILLISECONDS
        );
        scheduled.put(target.id(), f);
        log.info("Scheduled {} every {}ms (target {})", target.type(), target.pollIntervalMs(), target.id());
    }

    private void cancel(String id) {
        ScheduledFuture<?> f = scheduled.remove(id);
        if (f != null) {
            f.cancel(false);
            log.info("Cancelled schedule for target {}", id);
        }
    }

    private void dispatch(DbTargetConfig target, Collector collector) {
        // Resolve the pool on every tick so an event-driven rebuild takes effect immediately —
        // no risk of dispatching against a closed HikariDataSource.
        Optional<HikariDataSource> dsOpt = dataSources.dataSourceFor(target.id());
        if (dsOpt.isEmpty()) {
            log.debug("No DataSource for target {} — pool likely rebuilding; skipping this tick.", target.id());
            return;
        }
        HikariDataSource ds = dsOpt.get();
        long timeout = props.scheduler().collectionTimeoutMs();
        CompletableFuture
                .supplyAsync(() -> safeCollect(target, collector, ds), workers)
                .orTimeout(timeout, TimeUnit.MILLISECONDS)
                .whenComplete((snaps, err) -> {
                    if (err != null) {
                        log.warn("Collection failed for target={} type={}: {}",
                                target.id(), target.type(), err.toString());
                        return;
                    }
                    if (snaps != null) snaps.forEach(sink::emit);
                });
    }

    private List<MetricSnapshot> safeCollect(DbTargetConfig target, Collector collector, HikariDataSource ds) {
        try {
            return collector.collect(target, ds);
        } catch (Exception e) {
            throw new RuntimeException("collect() threw for " + target.id(), e);
        }
    }

    @PreDestroy
    public void stop() {
        scheduled.values().forEach(f -> f.cancel(false));
        scheduled.clear();
        if (ticker  != null) ticker.shutdownNow();
        if (workers != null) workers.shutdownNow();
        log.info("CollectorScheduler stopped.");
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
