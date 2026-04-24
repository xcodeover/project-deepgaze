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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scrapes node_exporter-style Prometheus text endpoints on the ACTUAL DB host
 * and projects the samples into the same `hostCpu` / `hostMemory` / `hostDisk`
 * snapshot shape that the Infrastructure Hub tile already consumes. When set,
 * this collector fully replaces {@link HostMetricsCollector} for the target —
 * OSHI describes the backend JVM's host, which is the wrong box for a remote
 * DB.
 *
 * Activation: a target opts in by setting `host-exporter-url` in yaml. Targets
 * without the URL are owned by OSHI; this collector silently ignores them.
 *
 * Scraping model:
 *   - 2s cadence, matching the OSHI collector so existing window math is unchanged
 *   - HTTP timeouts short (connect 1s, read 1.5s) — one dead scrape must not stall the next
 *   - Per-target worker pool so one slow endpoint can't delay the others
 *   - Counter deltas computed against the previous scrape of the same target;
 *     the first scrape emits only the gauge-style memory group (rates need two points)
 *
 * Allowlist filters at parse time — node_exporter ships ~1000 samples; we keep ~10.
 *
 * Emission is funnelled through a single-thread emitter so the MetricSink never
 * observes concurrent signals from this collector, per Reactor Sinks Rule 1.3
 * (mirrors HostMetricsCollector / BusinessMetricsCollector).
 */
@Slf4j
@Component
public class RemoteHostCollector {

    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT    = Duration.ofMillis(1_500);

    /** Everything the collector actually needs — the parser drops the rest. */
    private static final Set<String> ALLOWLIST = Set.of(
            "node_cpu_seconds_total",
            "node_memory_MemTotal_bytes",
            "node_memory_MemAvailable_bytes",
            "node_memory_MemFree_bytes",
            "node_memory_Buffers_bytes",
            "node_memory_Cached_bytes",
            "node_memory_SwapTotal_bytes",
            "node_memory_SwapFree_bytes",
            "node_disk_reads_completed_total",
            "node_disk_writes_completed_total",
            "node_disk_read_bytes_total",
            "node_disk_written_bytes_total",
            "node_disk_io_time_seconds_total",
            "node_disk_io_now",
            "node_filesystem_size_bytes",
            "node_filesystem_avail_bytes"
    );

    private final TargetRegistry targets;
    private final MetricSink sink;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private ScheduledExecutorService ticker;
    private ExecutorService workers;
    private ExecutorService emitter;
    private ScheduledFuture<?> tickFuture;

    /** Per-target rate state keyed by targetId. */
    private final Map<String, TargetState> state = new HashMap<>();

    public RemoteHostCollector(TargetRegistry targets, MetricSink sink) {
        this.targets = targets;
        this.sink = sink;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        List<DbTargetConfig> remote = targets.all().stream()
                .filter(DbTargetConfig::hasHostExporter).toList();
        if (remote.isEmpty()) {
            log.info("RemoteHostCollector idle — no targets configured with host-exporter-url.");
            return;
        }
        for (DbTargetConfig t : remote) {
            state.put(t.id(), new TargetState());
        }

        int workerCount = Math.max(1, remote.size());
        this.ticker  = Executors.newSingleThreadScheduledExecutor(named("dg-remote-tick"));
        this.workers = Executors.newFixedThreadPool(workerCount, named("dg-remote-scrape"));
        this.emitter = Executors.newSingleThreadExecutor(named("dg-remote-emit"));
        this.tickFuture = ticker.scheduleAtFixedRate(
                this::tick, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("RemoteHostCollector scheduled every {}ms (remote targets={}).",
                POLL_INTERVAL_MS, remote.size());
    }

    private void tick() {
        for (DbTargetConfig t : targets.all()) {
            if (!t.hasHostExporter()) continue;
            workers.execute(() -> scrapeOne(t));
        }
    }

    private void scrapeOne(DbTargetConfig t) {
        String body;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(t.hostExporterUrl()))
                    .timeout(READ_TIMEOUT)
                    .header("Accept", "text/plain")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.debug("Remote host scrape {}: HTTP {}", t.id(), resp.statusCode());
                return;
            }
            body = resp.body();
        } catch (Throwable ex) {
            log.debug("Remote host scrape {} failed: {}", t.id(), ex.toString());
            return;
        }

        List<PrometheusTextParser.Sample> samples = PrometheusTextParser.parse(body, ALLOWLIST);
        if (samples.isEmpty()) return;

        TargetState st = state.get(t.id());
        long nowMs = System.currentTimeMillis();
        Instant now = Instant.now();

        List<Map<String, Object>> cpuRows  = buildCpuRows(samples, st, nowMs);
        List<Map<String, Object>> memRows  = buildMemoryRows(samples);
        List<Map<String, Object>> diskRows = buildDiskRows(samples, st, nowMs);
        List<Map<String, Object>> fsRows   = buildFilesystemRows(samples);

        if (cpuRows != null)  emit(new MetricSnapshot(t.id(), t.displayName(), t.type(), now, "hostCpu",        cpuRows));
        if (memRows != null)  emit(new MetricSnapshot(t.id(), t.displayName(), t.type(), now, "hostMemory",     memRows));
        if (diskRows != null) emit(new MetricSnapshot(t.id(), t.displayName(), t.type(), now, "hostDisk",       diskRows));
        if (fsRows   != null) emit(new MetricSnapshot(t.id(), t.displayName(), t.type(), now, "hostFilesystem", fsRows));
    }

    /* ------------------------------- CPU -------------------------------- */

    /**
     * Sums per-cpu seconds per mode, diffs against previous scrape, then
     * normalises by the total delta so the percentages always add to 100.
     * Returns null on the first tick (no baseline to diff against).
     */
    private List<Map<String, Object>> buildCpuRows(
            List<PrometheusTextParser.Sample> samples, TargetState st, long nowMs) {

        Map<String, Double> perMode = new LinkedHashMap<>();
        int cores = 0;
        var cpuLabels = new java.util.HashSet<String>();
        for (var s : samples) {
            if (!"node_cpu_seconds_total".equals(s.name())) continue;
            String mode = s.labels().get("mode");
            String cpu  = s.labels().get("cpu");
            if (mode == null) continue;
            perMode.merge(mode, s.value(), Double::sum);
            if (cpu != null) cpuLabels.add(cpu);
        }
        cores = cpuLabels.size();
        if (perMode.isEmpty()) return null;

        Map<String, Double> prev = st.prevCpu;
        st.prevCpu = perMode;

        if (prev == null) return null;

        double total = 0.0;
        Map<String, Double> delta = new LinkedHashMap<>();
        for (var e : perMode.entrySet()) {
            double d = e.getValue() - prev.getOrDefault(e.getKey(), 0.0);
            if (d < 0) d = 0;       // counter reset after an exporter restart
            delta.put(e.getKey(), d);
            total += d;
        }
        if (total <= 0) return null;

        double user   = (delta.getOrDefault("user",   0.0) + delta.getOrDefault("nice", 0.0)) / total * 100.0;
        double system = (delta.getOrDefault("system", 0.0) + delta.getOrDefault("irq",  0.0)
                       + delta.getOrDefault("softirq", 0.0)) / total * 100.0;
        double iowait = delta.getOrDefault("iowait", 0.0) / total * 100.0;
        double idle   = delta.getOrDefault("idle",   0.0) / total * 100.0;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user_pct",   round1(user));
        row.put("system_pct", round1(system));
        row.put("iowait_pct", round1(iowait));
        row.put("idle_pct",   round1(idle));
        row.put("cores",      cores);
        return List.of(row);
    }

    /* ----------------------------- MEMORY ------------------------------- */

    /**
     * Gauges — no delta math. Used = total - available, falling back to
     * free + buffers + cached on kernels that don't export MemAvailable
     * (pre-3.14; we emit -1 rather than an incorrect value).
     */
    private List<Map<String, Object>> buildMemoryRows(List<PrometheusTextParser.Sample> samples) {
        Double total = null, available = null, free = null, buffers = null, cached = null;
        Double swapTotal = null, swapFree = null;
        for (var s : samples) {
            switch (s.name()) {
                case "node_memory_MemTotal_bytes"     -> total     = s.value();
                case "node_memory_MemAvailable_bytes" -> available = s.value();
                case "node_memory_MemFree_bytes"      -> free      = s.value();
                case "node_memory_Buffers_bytes"      -> buffers   = s.value();
                case "node_memory_Cached_bytes"       -> cached    = s.value();
                case "node_memory_SwapTotal_bytes"    -> swapTotal = s.value();
                case "node_memory_SwapFree_bytes"     -> swapFree  = s.value();
                default -> { }
            }
        }
        if (total == null) return null;

        double availBytes;
        if (available != null) {
            availBytes = available;
        } else if (free != null) {
            availBytes = free + (buffers == null ? 0 : buffers) + (cached == null ? 0 : cached);
        } else {
            return null;
        }
        double usedBytes = Math.max(0.0, total - availBytes);

        double swTotal = swapTotal == null ? 0.0 : swapTotal;
        double swUsed  = swapFree == null  ? 0.0 : Math.max(0.0, swTotal - swapFree);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("total_mb",      toMb(total));
        row.put("used_mb",       toMb(usedBytes));
        row.put("available_mb",  toMb(availBytes));
        row.put("used_pct",      total > 0 ? round1(100.0 * usedBytes / total) : 0.0);
        row.put("swap_total_mb", toMb(swTotal));
        row.put("swap_used_mb",  toMb(swUsed));
        row.put("swap_used_pct", swTotal > 0 ? round1(100.0 * swUsed / swTotal) : 0.0);
        return List.of(row);
    }

    /* ------------------------------- DISK ------------------------------- */

    /**
     * One row per physical disk device. loop/ram/dm- devices are skipped — they clutter
     * the table and inflate aggregate util. Rates need two scrapes; first tick
     * returns null. TreeMap keeps rows sorted by device name so the UI is stable.
     */
    private List<Map<String, Object>> buildDiskRows(
            List<PrometheusTextParser.Sample> samples, TargetState st, long nowMs) {

        Map<String, DiskCurr> curr = new TreeMap<>();
        for (var s : samples) {
            String dev = s.labels().get("device");
            if (dev == null || isUninterestingDevice(dev)) continue;
            DiskCurr c = curr.computeIfAbsent(dev, k -> new DiskCurr());
            switch (s.name()) {
                case "node_disk_reads_completed_total"  -> c.reads      = s.value();
                case "node_disk_writes_completed_total" -> c.writes     = s.value();
                case "node_disk_read_bytes_total"       -> c.readBytes  = s.value();
                case "node_disk_written_bytes_total"    -> c.writeBytes = s.value();
                case "node_disk_io_time_seconds_total"  -> c.ioTimeSec  = s.value();
                case "node_disk_io_now"                 -> c.ioNow      = s.value();
                default -> { }
            }
        }
        if (curr.isEmpty()) return null;

        Map<String, DiskCurr> prev = st.prevDisk;
        st.prevDisk = curr;
        long prevMs = st.prevDiskMs;
        st.prevDiskMs = nowMs;
        if (prev == null || prevMs == 0L) return null;

        double dtSec = Math.max(0.001, (nowMs - prevMs) / 1000.0);
        List<Map<String, Object>> rows = new ArrayList<>(curr.size());
        for (var e : curr.entrySet()) {
            DiskCurr c = e.getValue();
            DiskCurr p = prev.get(e.getKey());
            double rReadPs = 0, rWritePs = 0, mbReadPs = 0, mbWritePs = 0, utilPct = 0;
            if (p != null) {
                rReadPs   = rate(c.reads,      p.reads,      dtSec);
                rWritePs  = rate(c.writes,     p.writes,     dtSec);
                mbReadPs  = rate(c.readBytes,  p.readBytes,  dtSec) / 1024.0 / 1024.0;
                mbWritePs = rate(c.writeBytes, p.writeBytes, dtSec) / 1024.0 / 1024.0;
                double dIoTime = Math.max(0.0, c.ioTimeSec - p.ioTimeSec);
                utilPct = clamp(100.0 * dIoTime / dtSec, 0.0, 100.0);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name",           e.getKey());
            row.put("reads_per_s",    round1(rReadPs));
            row.put("writes_per_s",   round1(rWritePs));
            row.put("read_mb_per_s",  round2(mbReadPs));
            row.put("write_mb_per_s", round2(mbWritePs));
            row.put("queue",          (long) c.ioNow);
            row.put("util_pct",       round1(utilPct));
            rows.add(row);
        }
        return rows;
    }

    private static boolean isUninterestingDevice(String name) {
        return name.startsWith("loop") || name.startsWith("ram") || name.startsWith("dm-");
    }

    /* --------------------------- FILESYSTEM ----------------------------- */

    /**
     * One row per real filesystem. Virtual fstypes (tmpfs, devtmpfs, squashfs…)
     * and container/snap internal mountpoints are filtered out — a Docker host
     * exposes dozens of overlay mounts that are not actionable.
     *
     * The row's `name` key is set to the mountpoint so AlertRule's `label`
     * field can pin a rule to a specific filesystem (e.g. label=/var/lib/mysql),
     * via the MetricExtractor NAME_KEYS lookup.
     *
     * Gauges only — no rate math — so a single scrape is enough to emit. Returns
     * null only when node_exporter isn't shipping filesystem metrics at all.
     */
    private List<Map<String, Object>> buildFilesystemRows(List<PrometheusTextParser.Sample> samples) {
        Map<String, FsCurr> by = new TreeMap<>();
        for (var s : samples) {
            String mp     = s.labels().get("mountpoint");
            String fstype = s.labels().get("fstype");
            if (mp == null || !isInterestingMount(mp, fstype)) continue;
            FsCurr c = by.computeIfAbsent(mp, k -> new FsCurr(fstype));
            switch (s.name()) {
                case "node_filesystem_size_bytes"  -> c.size  = s.value();
                case "node_filesystem_avail_bytes" -> c.avail = s.value();
                default -> { }
            }
        }
        if (by.isEmpty()) return null;

        List<Map<String, Object>> rows = new ArrayList<>(by.size());
        for (var e : by.entrySet()) {
            FsCurr c = e.getValue();
            if (c.size <= 0) continue;
            double used = Math.max(0.0, c.size - c.avail);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name",       e.getKey());
            row.put("mountpoint", e.getKey());
            row.put("fstype",     c.fstype == null ? "" : c.fstype);
            row.put("size_gb",    round1(c.size  / 1024.0 / 1024.0 / 1024.0));
            row.put("used_gb",    round1(used    / 1024.0 / 1024.0 / 1024.0));
            row.put("avail_gb",   round1(c.avail / 1024.0 / 1024.0 / 1024.0));
            row.put("used_pct",   round1(100.0 * used / c.size));
            rows.add(row);
        }
        return rows.isEmpty() ? null : rows;
    }

    private static boolean isInterestingMount(String mountpoint, String fstype) {
        if (fstype != null) {
            switch (fstype) {
                case "tmpfs", "devtmpfs", "squashfs", "autofs", "fusectl", "proc", "sysfs",
                     "debugfs", "mqueue", "cgroup", "cgroup2", "pstore", "tracefs", "bpf",
                     "configfs", "securityfs", "hugetlbfs", "fuse.lxcfs", "rpc_pipefs",
                     "nsfs", "binfmt_misc", "ramfs" -> { return false; }
                default -> { }
            }
        }
        return !(mountpoint.startsWith("/var/lib/docker/")
                || mountpoint.startsWith("/var/lib/containers/")
                || mountpoint.startsWith("/snap/")
                || mountpoint.startsWith("/run/")
                || mountpoint.startsWith("/boot/efi")
                || mountpoint.startsWith("/dev/")   || mountpoint.equals("/dev")
                || mountpoint.startsWith("/proc/")  || mountpoint.equals("/proc")
                || mountpoint.startsWith("/sys/")   || mountpoint.equals("/sys"));
    }

    private static double rate(double curr, double prev, double dtSec) {
        double d = curr - prev;
        return d < 0 ? 0.0 : d / dtSec;
    }

    /* ---------------------------- plumbing ------------------------------ */

    private void emit(MetricSnapshot snap) {
        emitter.execute(() -> sink.emit(snap));
    }

    @PreDestroy
    public void stop() {
        if (tickFuture != null) tickFuture.cancel(false);
        if (ticker != null)  ticker.shutdownNow();
        if (workers != null) workers.shutdownNow();
        if (emitter != null) emitter.shutdownNow();
    }

    private static long   toMb(double bytes)       { return (long) (bytes / (1024.0 * 1024.0)); }
    private static double round1(double v)         { return Math.round(v * 10.0) / 10.0; }
    private static double round2(double v)         { return Math.round(v * 100.0) / 100.0; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Mutable per-target rate state — accessed only from the per-target worker thread. */
    private static final class TargetState {
        Map<String, Double> prevCpu;
        Map<String, DiskCurr> prevDisk;
        long prevDiskMs;
    }

    /** Mutable scratch for a single scrape's per-disk values. */
    private static final class DiskCurr {
        double reads, writes, readBytes, writeBytes, ioTimeSec, ioNow;
    }

    /** Mutable scratch for a single scrape's per-filesystem values. */
    private static final class FsCurr {
        final String fstype;
        double size, avail;
        FsCurr(String fstype) { this.fstype = fstype; }
    }
}
