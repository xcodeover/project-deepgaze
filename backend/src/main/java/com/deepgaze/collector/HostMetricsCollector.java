package com.deepgaze.collector;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.MetricSnapshot;
import com.deepgaze.stream.MetricSink;
import com.deepgaze.targets.TargetRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.VirtualMemory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OS-level metrics collector backed by OSHI.
 *
 * Observes the BACKEND host (the JVM running DeepGaze) — useful when the
 * monitor is co-located with the DB or as a baseline read of the monitoring
 * host itself. Cheap: one SystemInfo instance shared across ticks, diff
 * arithmetic on CPU ticks / per-disk counters for rates.
 *
 * Emission model mirrors BusinessMetricsCollector — SQL-less here but same
 * scheduling contract: a single-thread ticker fires the sampling, and a
 * dedicated single-thread emitter funnels sink.emit() calls so the broadcaster
 * never sees concurrent signals from this collector (Reactor Rule 1.3).
 *
 * Three groups per tick, replicated under each configured targetId so the
 * existing target-scoped UI selector surfaces them without special-casing:
 *   hostCpu     → single row with user/system/iowait/idle percentages
 *   hostMemory  → single row with memory + swap totals, used, percentages
 *   hostDisk    → one row per physical disk (reads/s, writes/s, MB/s, queue, util%)
 */
@Slf4j
@Component
public class HostMetricsCollector {

    private static final long POLL_INTERVAL_MS = 2_000L;

    private final TargetRegistry targets;
    private final MetricSink sink;

    private SystemInfo si;
    private HardwareAbstractionLayer hal;
    private CentralProcessor cpu;
    private GlobalMemory memory;
    private long[] prevTicks;

    private ScheduledExecutorService ticker;
    private ExecutorService emitter;
    private ScheduledFuture<?> tickFuture;

    /** Per-disk previous sample (counters + wall-clock) for rate math. */
    private final Map<String, DiskSample> prevDisk = new HashMap<>();

    public HostMetricsCollector(TargetRegistry targets, MetricSink sink) {
        this.targets = targets;
        this.sink = sink;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        List<DbTargetConfig> allTargets = targets.all();
        if (allTargets.isEmpty()) {
            log.info("HostMetricsCollector idle — no targets configured.");
            return;
        }
        // When every configured target has a host-exporter-url, RemoteHostCollector
        // fully owns the host/disk/memory groups. Skip OSHI init entirely so we
        // don't pay the sampling cost for data that will never be emitted.
        boolean anyLocal = allTargets.stream().anyMatch(t -> !t.hasHostExporter());
        if (!anyLocal) {
            log.info("HostMetricsCollector idle — all targets delegate to RemoteHostCollector.");
            return;
        }
        try {
            this.si = new SystemInfo();
            this.hal = si.getHardware();
            this.cpu = hal.getProcessor();
            this.memory = hal.getMemory();
            this.prevTicks = cpu.getSystemCpuLoadTicks();
        } catch (Throwable t) {
            log.warn("OSHI init failed — host metrics disabled: {}", t.toString());
            return;
        }

        this.ticker = Executors.newSingleThreadScheduledExecutor(named("dg-host-tick"));
        this.emitter = Executors.newSingleThreadExecutor(named("dg-host-emit"));
        this.tickFuture = ticker.scheduleAtFixedRate(
                this::tick, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("HostMetricsCollector scheduled every {}ms (targets={}).",
                POLL_INTERVAL_MS, allTargets.size());
    }

    private void tick() {
        try {
            List<Map<String, Object>> cpuRows  = buildCpuRows();
            List<Map<String, Object>> memRows  = buildMemoryRows();
            List<Map<String, Object>> diskRows = buildDiskRows();

            Instant now = Instant.now();
            for (DbTargetConfig t : targets.all()) {
                emit(new MetricSnapshot(t.id(), t.displayName(), t.type(), now, "hostCpu",    cpuRows));
                emit(new MetricSnapshot(t.id(), t.displayName(), t.type(), now, "hostMemory", memRows));
                emit(new MetricSnapshot(t.id(), t.displayName(), t.type(), now, "hostDisk",   diskRows));
            }
        } catch (Throwable t) {
            log.warn("Host metrics tick failed: {}", t.toString());
        }
    }

    private List<Map<String, Object>> buildCpuRows() {
        long[] currTicks = cpu.getSystemCpuLoadTicks();
        double[] pct = calcPct(prevTicks, currTicks);
        prevTicks = currTicks;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user_pct",   round1(pct[CentralProcessor.TickType.USER.getIndex()]
                                 + pct[CentralProcessor.TickType.NICE.getIndex()]));
        row.put("system_pct", round1(pct[CentralProcessor.TickType.SYSTEM.getIndex()]
                                 + pct[CentralProcessor.TickType.IRQ.getIndex()]
                                 + pct[CentralProcessor.TickType.SOFTIRQ.getIndex()]));
        row.put("iowait_pct", round1(pct[CentralProcessor.TickType.IOWAIT.getIndex()]));
        row.put("idle_pct",   round1(pct[CentralProcessor.TickType.IDLE.getIndex()]));
        row.put("cores", cpu.getLogicalProcessorCount());
        return List.of(row);
    }

    private static double[] calcPct(long[] prev, long[] curr) {
        double[] out = new double[curr.length];
        long total = 0L;
        for (int i = 0; i < curr.length; i++) total += Math.max(0L, curr[i] - prev[i]);
        if (total <= 0L) return out;
        for (int i = 0; i < curr.length; i++) {
            out[i] = 100.0 * Math.max(0L, curr[i] - prev[i]) / total;
        }
        return out;
    }

    private List<Map<String, Object>> buildMemoryRows() {
        VirtualMemory vm = memory.getVirtualMemory();
        long totalB = memory.getTotal();
        long availB = memory.getAvailable();
        long usedB = Math.max(0L, totalB - availB);
        long swapTotal = vm.getSwapTotal();
        long swapUsed = vm.getSwapUsed();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("total_mb",       toMb(totalB));
        row.put("used_mb",        toMb(usedB));
        row.put("available_mb",   toMb(availB));
        row.put("used_pct",       totalB > 0 ? round1(100.0 * usedB / totalB) : 0.0);
        row.put("swap_total_mb",  toMb(swapTotal));
        row.put("swap_used_mb",   toMb(swapUsed));
        row.put("swap_used_pct",  swapTotal > 0 ? round1(100.0 * swapUsed / swapTotal) : 0.0);
        return List.of(row);
    }

    private List<Map<String, Object>> buildDiskRows() {
        List<HWDiskStore> disks = hal.getDiskStores();
        List<Map<String, Object>> rows = new ArrayList<>(disks.size());
        long nowMs = System.currentTimeMillis();

        for (HWDiskStore d : disks) {
            // Refreshes reads/writes/transferTime counters in-place.
            d.updateAttributes();

            String name = d.getName();
            long reads = d.getReads();
            long writes = d.getWrites();
            long readBytes = d.getReadBytes();
            long writeBytes = d.getWriteBytes();
            long transferMs = d.getTransferTime();
            long queue = d.getCurrentQueueLength();

            DiskSample prev = prevDisk.get(name);
            DiskSample curr = new DiskSample(nowMs, reads, writes, readBytes, writeBytes, transferMs);
            prevDisk.put(name, curr);

            double rReadPs = 0, rWritePs = 0, mbReadPs = 0, mbWritePs = 0, utilPct = 0;
            if (prev != null) {
                double dtSec = Math.max(0.001, (curr.tMs - prev.tMs) / 1000.0);
                rReadPs   = Math.max(0.0, (reads      - prev.reads)      / dtSec);
                rWritePs  = Math.max(0.0, (writes     - prev.writes)     / dtSec);
                mbReadPs  = Math.max(0.0, (readBytes  - prev.readBytes)  / dtSec / 1024.0 / 1024.0);
                mbWritePs = Math.max(0.0, (writeBytes - prev.writeBytes) / dtSec / 1024.0 / 1024.0);
                double dTransfer = Math.max(0.0, transferMs - prev.transferMs);
                utilPct = clamp(100.0 * dTransfer / (dtSec * 1000.0), 0.0, 100.0);
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name",            name);
            row.put("reads_per_s",     round1(rReadPs));
            row.put("writes_per_s",    round1(rWritePs));
            row.put("read_mb_per_s",   round2(mbReadPs));
            row.put("write_mb_per_s",  round2(mbWritePs));
            row.put("queue",           queue);
            row.put("util_pct",        round1(utilPct));
            rows.add(row);
        }
        return rows;
    }

    /** Serial hand-off mirrors BusinessMetricsCollector — Reactor Rule 1.3. */
    private void emit(MetricSnapshot snap) {
        emitter.execute(() -> sink.emit(snap));
    }

    @PreDestroy
    public void stop() {
        if (tickFuture != null) tickFuture.cancel(false);
        if (ticker != null) ticker.shutdownNow();
        if (emitter != null) emitter.shutdownNow();
    }

    private static long   toMb(long bytes)       { return bytes / (1024L * 1024L); }
    private static double round1(double v)       { return Math.round(v * 10.0) / 10.0; }
    private static double round2(double v)       { return Math.round(v * 100.0) / 100.0; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Per-disk sample snapshot used for rate math between adjacent ticks. */
    private record DiskSample(
            long tMs, long reads, long writes, long readBytes, long writeBytes, long transferMs) {}

    private static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
