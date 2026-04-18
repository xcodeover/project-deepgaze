package com.deepgaze.collector;

import com.deepgaze.config.DataSourceRegistry;
import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.MetricSnapshot;
import com.deepgaze.stream.MetricSink;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
 * The ticker thread NEVER blocks: each tick only submits a CompletableFuture and returns.
 * If a target hangs (e.g. Oracle dictionary stalls), only one worker is occupied until
 * the per-collection timeout fires. Other targets continue ticking on their own schedule.
 *
 * Note: orTimeout completes the future exceptionally but does not interrupt JDBC. Each
 * Collector implementation must set Statement#setQueryTimeout to bound the actual call.
 *
 * Initialization runs on ApplicationReadyEvent (not @PostConstruct) so every subscriber
 * to MetricStream — most importantly the MetricRingBuffer — is registered before the
 * first tick fires, ensuring no startup events are dropped.
 */
@Slf4j
@Component
public class CollectorScheduler {

    private final DeepGazeProperties props;
    private final DataSourceRegistry dataSources;
    private final CollectorRegistry collectors;
    private final MetricSink sink;

    private ScheduledExecutorService ticker;
    private ExecutorService workers;
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();

    public CollectorScheduler(DeepGazeProperties props,
                              DataSourceRegistry dataSources,
                              CollectorRegistry collectors,
                              MetricSink sink) {
        this.props = props;
        this.dataSources = dataSources;
        this.collectors = collectors;
        this.sink = sink;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        this.ticker  = Executors.newSingleThreadScheduledExecutor(named("dg-tick"));
        this.workers = Executors.newFixedThreadPool(props.scheduler().workerPoolSize(), named("dg-worker"));

        for (DbTargetConfig target : props.targets()) {
            Optional<Collector> collector = collectors.forType(target.type());
            if (collector.isEmpty()) {
                log.warn("No Collector registered for type {} (target={}). Skipping schedule.",
                        target.type(), target.id());
                continue;
            }
            Optional<HikariDataSource> ds = dataSources.dataSourceFor(target.id());
            if (ds.isEmpty()) {
                log.warn("No DataSource for target {} — pool init may have failed. Skipping schedule.",
                        target.id());
                continue;
            }
            ScheduledFuture<?> f = ticker.scheduleAtFixedRate(
                    () -> dispatch(target, collector.get(), ds.get()),
                    0L, target.pollIntervalMs(), TimeUnit.MILLISECONDS
            );
            scheduled.add(f);
            log.info("Scheduled {} every {}ms (target {})", target.type(), target.pollIntervalMs(), target.id());
        }
    }

    private void dispatch(DbTargetConfig target, Collector collector, HikariDataSource ds) {
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
        scheduled.forEach(f -> f.cancel(false));
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
