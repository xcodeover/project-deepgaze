package com.deepgaze.history;

import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.MetricSnapshot;
import com.deepgaze.stream.MetricStream;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges the live {@link MetricStream} into the SQLite {@link HistoryStore}.
 * Architecture:
 *
 *   reactor Flux  --onNext-->  ArrayBlockingQueue (drop-oldest on full)
 *                                         |
 *                                 dedicated writer thread
 *                               drains up to batchSize or waits batchIntervalMs
 *                                         |
 *                                  HistoryStore.insertBatch
 *
 * Drop-oldest is deliberate: a surge should discard the stalest history,
 * not push back on the reactor pipeline (which would starve SSE clients).
 * A separate {@link ScheduledExecutorService} runs the periodic purge off
 * the writer thread so a slow DELETE can never delay a batch flush.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "deepgaze.history", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MetricsPersisterService {

    private final MetricStream stream;
    private final HistoryStore store;
    private final DeepGazeProperties props;
    private final HistoryConfigService historyConfig;

    private final ArrayBlockingQueue<MetricSnapshot> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();

    private volatile Disposable subscription;
    private volatile Thread writerThread;
    private volatile boolean running = false;
    private ScheduledExecutorService purgeScheduler;

    public MetricsPersisterService(MetricStream stream,
                                   HistoryStore store,
                                   DeepGazeProperties props,
                                   HistoryConfigService historyConfig) {
        this.stream = stream;
        this.store = store;
        this.props = props;
        this.historyConfig = historyConfig;
        this.queue = new ArrayBlockingQueue<>(props.history().queueCapacity());
    }

    @PostConstruct
    public void start() {
        running = true;

        this.subscription = stream.stream().subscribe(this::enqueue, err ->
                log.warn("History subscription error: {}", err.toString()));

        this.writerThread = new Thread(this::runWriter, "deepgaze-history-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();

        long purgeMinutes = Math.max(1L, props.history().purgeIntervalMinutes());
        this.purgeScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "deepgaze-history-purge");
            t.setDaemon(true);
            return t;
        });
        this.purgeScheduler.scheduleAtFixedRate(this::runPurge,
                purgeMinutes, purgeMinutes, TimeUnit.MINUTES);

        log.info("MetricsPersisterService started: batchSize={} intervalMs={} queueCapacity={} retentionHours={} (runtime-mutable)",
                props.history().batchSize(),
                props.history().batchIntervalMs(),
                props.history().queueCapacity(),
                historyConfig.current().retentionHours());
    }

    private void enqueue(MetricSnapshot snap) {
        if (!running || snap == null) return;
        if (!queue.offer(snap)) {
            // Drop-oldest: evict head and retry once. If the retry still
            // fails the queue was drained in parallel — just swallow.
            queue.poll();
            if (!queue.offer(snap)) return;
            long d = dropped.incrementAndGet();
            if (d == 1 || d % 1000 == 0) {
                log.warn("History queue saturated — dropped {} oldest snapshots so far", d);
            }
        }
    }

    private void runWriter() {
        int batchSize = Math.max(1, props.history().batchSize());
        long waitMs   = Math.max(1L, props.history().batchIntervalMs());
        List<MetricSnapshot> batch = new ArrayList<>(batchSize);

        while (running || !queue.isEmpty()) {
            try {
                MetricSnapshot head = queue.poll(waitMs, TimeUnit.MILLISECONDS);
                if (head != null) batch.add(head);
                queue.drainTo(batch, batchSize - batch.size());

                if (!batch.isEmpty()) {
                    int n = store.insertBatch(batch);
                    long total = written.addAndGet(n);
                    if (total < 50 || total % 500 == 0) {
                        log.debug("History persisted batch size={} totalRows={}", n, total);
                    }
                    batch.clear();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("History writer loop error: {}", e.toString());
                batch.clear();
            }
        }

        // Final drain on shutdown.
        List<MetricSnapshot> tail = new ArrayList<>();
        queue.drainTo(tail);
        if (!tail.isEmpty()) {
            int n = store.insertBatch(tail);
            log.info("History writer stopped — final drain wrote {} rows", n);
        }
    }

    private void runPurge() {
        try {
            int retentionHours = historyConfig.current().retentionHours();
            long cutoff = Instant.now().minus(Duration.ofHours(retentionHours)).toEpochMilli();
            int deleted = store.purgeOlderThan(cutoff);
            if (deleted > 0) {
                log.info("History purge: removed {} rows older than {}h", deleted, retentionHours);
            }
        } catch (Exception e) {
            log.warn("History purge tick failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        if (purgeScheduler != null) {
            purgeScheduler.shutdownNow();
        }
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                writerThread.join(5_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("MetricsPersisterService stopped: totalWritten={} totalDropped={}",
                written.get(), dropped.get());
    }
}
