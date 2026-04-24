package com.deepgaze.queryhistory;

import com.deepgaze.history.HistoryConfigService;
import com.deepgaze.model.MetricSnapshot;
import com.deepgaze.stream.MetricStream;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Subscribes to {@link MetricStream} and flattens the {@code topDigests} /
 * {@code slowQueries} group rows into the narrow {@link QueryEventStore}
 * schema. Mirrors {@link com.deepgaze.history.MetricsPersisterService}'s
 * architecture (queue → writer thread → batched INSERT) but dedupes per
 * {@code (target, digest)} to a one-minute bucket so the search table stays
 * tractable even though the upstream collector polls every second.
 *
 * Retention is shared with Time Machine ({@link HistoryConfigService}) — one
 * knob in the Settings UI governs both tables, which matches operator
 * expectations ("how far back can I look").
 */
@Slf4j
@Service
public class QueryEventPersister {

    private static final int QUEUE_CAPACITY = 5_000;
    private static final int BATCH_SIZE     = 100;
    private static final long BATCH_WAIT_MS = 500L;
    private static final long DEDUPE_BUCKET_MS = 60_000L;

    private final MetricStream stream;
    private final QueryEventStore store;
    private final HistoryConfigService retention;

    private final ArrayBlockingQueue<QueryEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Map<String, Long> lastBucket = new HashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();

    private volatile Disposable subscription;
    private volatile Thread writerThread;
    private volatile boolean running = false;
    private ScheduledExecutorService purgeScheduler;

    public QueryEventPersister(MetricStream stream,
                               QueryEventStore store,
                               HistoryConfigService retention) {
        this.stream = stream;
        this.store = store;
        this.retention = retention;
    }

    @PostConstruct
    public void start() {
        running = true;
        this.subscription = stream.stream().subscribe(this::onSnapshot, err ->
                log.warn("QueryEventPersister subscription error: {}", err.toString()));

        this.writerThread = new Thread(this::runWriter, "deepgaze-query-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();

        this.purgeScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "deepgaze-query-purge");
            t.setDaemon(true);
            return t;
        });
        this.purgeScheduler.scheduleAtFixedRate(this::runPurge, 60, 60, TimeUnit.MINUTES);

        log.info("QueryEventPersister started (retentionHours={})",
                retention.current().retentionHours());
    }

    private void onSnapshot(MetricSnapshot snap) {
        if (!running || snap == null || snap.rows() == null) return;
        String group = snap.metricGroup();
        if (!"topDigests".equals(group) && !"slowQueries".equals(group)) return;

        long tsMs = snap.timestamp() == null ? System.currentTimeMillis() : snap.timestamp().toEpochMilli();
        long bucket = tsMs / DEDUPE_BUCKET_MS;

        for (Map<String, Object> row : snap.rows()) {
            String digest = str(row.get("digest"));
            if (digest == null || digest.isBlank()) continue;
            String key = snap.targetId() + "|" + group + "|" + digest;
            Long last = lastBucket.get(key);
            if (last != null && last == bucket) continue;   // already written in this minute
            lastBucket.put(key, bucket);

            QueryEvent ev = new QueryEvent(
                    snap.targetId(),
                    snap.targetName(),
                    snap.type() == null ? null : snap.type().name(),
                    tsMs,
                    digest,
                    str(row.get("digest_text")),
                    asLong  (row.get("count_star")),
                    asLong  (row.get("sum_rows_sent")),
                    asLong  (row.get("sum_rows_examined")),
                    asDouble(row.get("avg_timer_wait")),
                    asDouble(row.get("sum_timer_wait")),
                    group
            );

            if (!queue.offer(ev)) {
                queue.poll();
                if (!queue.offer(ev)) {
                    long d = dropped.incrementAndGet();
                    if (d == 1 || d % 500 == 0) {
                        log.warn("QueryEvent queue saturated — dropped {} total", d);
                    }
                }
            }
        }

        // Keep the dedupe map from growing without bound — sweep entries whose
        // bucket is > 2 minutes behind the latest we've seen.
        if (lastBucket.size() > 50_000) {
            long threshold = bucket - 2;
            lastBucket.entrySet().removeIf(e -> e.getValue() < threshold);
        }
    }

    private void runWriter() {
        List<QueryEvent> batch = new ArrayList<>(BATCH_SIZE);
        while (running || !queue.isEmpty()) {
            try {
                QueryEvent head = queue.poll(BATCH_WAIT_MS, TimeUnit.MILLISECONDS);
                if (head != null) batch.add(head);
                queue.drainTo(batch, BATCH_SIZE - batch.size());
                if (!batch.isEmpty()) {
                    int n = store.insertBatch(batch);
                    long total = written.addAndGet(n);
                    if (total < 50 || total % 500 == 0) {
                        log.debug("QueryEvent persisted batch={} total={}", n, total);
                    }
                    batch.clear();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("QueryEvent writer loop error: {}", e.toString());
                batch.clear();
            }
        }
        List<QueryEvent> tail = new ArrayList<>();
        queue.drainTo(tail);
        if (!tail.isEmpty()) store.insertBatch(tail);
    }

    private void runPurge() {
        try {
            int hours = retention.current().retentionHours();
            long cutoff = Instant.now().minus(Duration.ofHours(hours)).toEpochMilli();
            int deleted = store.purgeOlderThan(cutoff);
            if (deleted > 0) {
                log.info("QueryEvent purge: removed {} rows older than {}h", deleted, hours);
            }
        } catch (Exception e) {
            log.warn("QueryEvent purge tick failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (subscription != null && !subscription.isDisposed()) subscription.dispose();
        if (purgeScheduler != null) purgeScheduler.shutdownNow();
        if (writerThread != null) {
            writerThread.interrupt();
            try { writerThread.join(5_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        log.info("QueryEventPersister stopped: totalWritten={} totalDropped={}",
                written.get(), dropped.get());
    }

    /* ---- coercion helpers ---- */

    private static String str(Object v) { return v == null ? null : v.toString(); }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString().trim()); } catch (Exception e) { return null; }
    }

    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString().trim()); } catch (Exception e) { return null; }
    }
}
