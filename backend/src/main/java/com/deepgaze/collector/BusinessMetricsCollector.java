package com.deepgaze.collector;

import com.deepgaze.config.DataSourceRegistry;
import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.config.DeepGazeProperties.BusinessMetric;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.model.MetricSnapshot;
import com.deepgaze.stream.MetricSink;
import com.deepgaze.targets.TargetRegistry;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Config-driven KPI collector for the "Business Scoreboard" strip.
 *
 * Why a dedicated collector instead of another MariaDbMapper method: the SQL
 * is user-defined and varies per installation — it can't be a compile-time
 * annotation. Raw JDBC on the existing per-target HikariCP DataSource keeps
 * us out of the dynamic-MyBatis rabbit hole (@SelectProvider + Configuration
 * mutation at runtime) while still giving us Statement#setQueryTimeout and
 * the pool's native connection leak detection.
 *
 * Scheduling: one single-thread ticker fires every 1s and dispatches any
 * metric whose `nextRunAt` has elapsed onto a small worker pool. Per-metric
 * pollIntervalMs lets an expensive nightly rollup run every 5m side-by-side
 * with a 2s live P&L without one blocking the other.
 *
 * Emission: whenever a metric finishes (success or error), we re-emit the
 * full per-target snapshot with the latest value for every metric belonging
 * to that target. The frontend just reads the most recent "businessMetrics"
 * snapshot per target and renders — no merging logic required on the UI.
 */
@Slf4j
@Component
public class BusinessMetricsCollector {

    /** Hard upper bound on any single business-metric query (seconds, JDBC units). */
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private static final String GROUP = "businessMetrics";

    private final DeepGazeProperties props;
    private final TargetRegistry targets;
    private final DataSourceRegistry dataSources;
    private final MetricSink sink;

    /** Per-target rolling state: latest-row cache + next-run schedule, keyed by metric id. */
    private final Map<String, TargetState> byTarget = new HashMap<>();

    /** DbType lookup for the MetricSnapshot header — looked up once at startup. */
    private final Map<String, DbType> typeByTarget = new HashMap<>();

    /** Display name lookup for the MetricSnapshot header — looked up once at startup. */
    private final Map<String, String> nameByTarget = new HashMap<>();

    private ScheduledExecutorService ticker;
    private ExecutorService workers;
    /**
     * Dedicated single-thread executor for sink.emit(). Prevents two workers
     * from calling emitNext concurrently, which violates Reactor's Rule 1.3
     * (serial signalling) and times out the broadcaster's 10ms busy-loop.
     * SQL execution stays parallel on `workers`; only the emission funnels.
     */
    private ExecutorService emitter;
    private ScheduledFuture<?> tickFuture;

    public BusinessMetricsCollector(DeepGazeProperties props,
                                    TargetRegistry targets,
                                    DataSourceRegistry dataSources,
                                    MetricSink sink) {
        this.props = props;
        this.targets = targets;
        this.dataSources = dataSources;
        this.sink = sink;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        List<BusinessMetric> metrics = props.businessMetrics();
        if (metrics == null || metrics.isEmpty()) {
            log.info("No business metrics configured — BusinessMetricsCollector idle.");
            return;
        }

        for (DbTargetConfig t : targets.all()) {
            typeByTarget.put(t.id(), t.type());
            nameByTarget.put(t.id(), t.displayName());
        }
        for (BusinessMetric m : metrics) {
            if (!typeByTarget.containsKey(m.target())) {
                log.warn("Business metric {} references unknown target {} — skipping.", m.id(), m.target());
                continue;
            }
            byTarget.computeIfAbsent(m.target(), k -> new TargetState());
        }
        if (byTarget.isEmpty()) {
            log.info("All configured business metrics reference unknown targets — collector idle.");
            return;
        }

        this.ticker  = Executors.newSingleThreadScheduledExecutor(named("dg-bmetric-tick"));
        this.workers = Executors.newFixedThreadPool(4, named("dg-bmetric-worker"));
        this.emitter = Executors.newSingleThreadExecutor(named("dg-bmetric-emit"));
        // Start at 1s to let the schema warm up; then recheck every second.
        this.tickFuture = ticker.scheduleAtFixedRate(
                this::tick, 1_000L, 1_000L, TimeUnit.MILLISECONDS);

        log.info("BusinessMetricsCollector scheduled {} metrics across {} target(s).",
                metrics.size(), byTarget.size());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (BusinessMetric m : props.businessMetrics()) {
            TargetState state = byTarget.get(m.target());
            if (state == null) continue;
            Long nextRun = state.nextRunAt.get(m.id());
            if (nextRun != null && now < nextRun) continue;
            state.nextRunAt.put(m.id(), now + m.pollIntervalMs());
            workers.submit(() -> runMetric(m));
        }
    }

    private void runMetric(BusinessMetric m) {
        Optional<HikariDataSource> dsOpt = dataSources.dataSourceFor(m.target());
        if (dsOpt.isEmpty()) {
            log.warn("Business metric {}: no DataSource for target {}", m.id(), m.target());
            return;
        }

        Object value = null;
        String error = null;
        long startNs = System.nanoTime();

        try (Connection c = dsOpt.get().getConnection();
             Statement st = c.createStatement()) {
            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = st.executeQuery(m.sql())) {
                if (rs.next()) value = rs.getObject(1);
            }
        } catch (Exception e) {
            error = rootMessage(e);
            log.warn("Business metric {} failed: {}", m.id(), error);
        }

        long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
        updateAndEmit(m, value, error, latencyMs);
    }

    private void updateAndEmit(BusinessMetric m, Object value, String error, long latencyMs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", m.id());
        row.put("label", m.label());
        row.put("value", value);
        row.put("format", m.format());
        row.put("latency_ms", latencyMs);
        row.put("updated_at", Instant.now().toString());
        if (error != null) row.put("error", error);

        TargetState state = byTarget.get(m.target());
        state.latestById.put(m.id(), row);

        // Emit the full per-target snapshot so the frontend always has the
        // freshest known value for every metric on that target, regardless
        // of which individual query just fired. Walk the config list (not
        // the cache) so rows come out in the user-declared order even though
        // workers may finish out of order.
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BusinessMetric def : props.businessMetrics()) {
            if (!def.target().equals(m.target())) continue;
            Map<String, Object> cached = state.latestById.get(def.id());
            if (cached != null) rows.add(cached);
        }
        MetricSnapshot snap = new MetricSnapshot(
                m.target(),
                nameByTarget.getOrDefault(m.target(), m.target()),
                typeByTarget.get(m.target()),
                Instant.now(),
                GROUP,
                rows);
        // Serial hand-off: only ever one emit in flight from this collector,
        // so the broadcaster's busy-loop never has to arbitrate internal races.
        emitter.execute(() -> sink.emit(snap));
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return msg != null ? msg : cur.getClass().getSimpleName();
    }

    @PreDestroy
    public void stop() {
        if (tickFuture != null) tickFuture.cancel(false);
        if (ticker  != null) ticker.shutdownNow();
        if (workers != null) workers.shutdownNow();
        if (emitter != null) emitter.shutdownNow();
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Per-target state holder — latest row cache and next-run scheduling map. */
    private static final class TargetState {
        final Map<String, Map<String, Object>> latestById = new ConcurrentHashMap<>();
        final Map<String, Long> nextRunAt = new ConcurrentHashMap<>();
    }
}
