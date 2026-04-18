package com.deepgaze.queue;

import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.MetricSnapshot;
import com.deepgaze.stream.MetricStream;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, thread-safe FIFO buffer of metric snapshots.
 * On overflow the oldest entry is evicted (drop-oldest) — newest data wins,
 * OOM is impossible.
 *
 * The buffer is a passive subscriber to MetricStream — it does not pull from
 * the scheduler and the scheduler does not push to it. This keeps the writer
 * (scheduler) decoupled from every reader (OCP) and gives every component a
 * single responsibility (SRP).
 */
@Slf4j
@Component
public class MetricRingBuffer implements MetricBufferReader {

    private final int capacity;
    private final ArrayDeque<MetricSnapshot> deque;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong evictedCount = new AtomicLong();
    private final AtomicLong acceptedCount = new AtomicLong();

    private final MetricStream stream;
    private Disposable subscription;

    public MetricRingBuffer(DeepGazeProperties props, MetricStream stream) {
        this.capacity = props.buffer().capacity();
        this.deque = new ArrayDeque<>(capacity);
        this.stream = stream;
        log.info("MetricRingBuffer initialized with capacity={}", capacity);
    }

    @PostConstruct
    void subscribe() {
        this.subscription = stream.stream().subscribe(
                this::append,
                err -> log.error("MetricRingBuffer subscription error", err)
        );
        log.info("MetricRingBuffer subscribed to MetricStream");
    }

    @PreDestroy
    void unsubscribe() {
        if (subscription != null) subscription.dispose();
    }

    private void append(MetricSnapshot snapshot) {
        if (snapshot == null) return;
        lock.lock();
        try {
            if (deque.size() >= capacity) {
                deque.pollFirst();
                evictedCount.incrementAndGet();
            }
            deque.offerLast(snapshot);
            acceptedCount.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<MetricSnapshot> snapshot() {
        lock.lock();
        try {
            return new ArrayList<>(deque);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<MetricSnapshot> since(Instant since) {
        lock.lock();
        try {
            List<MetricSnapshot> out = new ArrayList<>();
            Iterator<MetricSnapshot> it = deque.descendingIterator();
            while (it.hasNext()) {
                MetricSnapshot s = it.next();
                if (s.timestamp().isAfter(since)) out.add(0, s); else break;
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try { return deque.size(); } finally { lock.unlock(); }
    }

    @Override public int  capacity() { return capacity; }
    @Override public long accepted() { return acceptedCount.get(); }
    @Override public long evicted()  { return evictedCount.get();  }
}
