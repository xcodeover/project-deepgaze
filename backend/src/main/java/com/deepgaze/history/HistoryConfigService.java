package com.deepgaze.history;

import com.deepgaze.config.DeepGazeProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Hot-read cache in front of {@link HistoryConfigStore}. The purge tick in
 * {@link MetricsPersisterService} calls {@link #current()} every minute, so
 * an UI-initiated retention change takes effect on the next purge without
 * a restart.
 *
 * First boot falls back to {@code deepgaze.history.retention-hours} from
 * {@code application.yml}; subsequent boots read whatever the operator last
 * saved through {@code PUT /api/settings/history}.
 */
@Slf4j
@Service
public class HistoryConfigService {

    private final HistoryConfigStore store;
    private final int defaultHours;
    private final AtomicReference<HistoryConfig> cache = new AtomicReference<>();

    public HistoryConfigService(HistoryConfigStore store, DeepGazeProperties props) {
        this.store = store;
        this.defaultHours = HistoryConfig.clamp(props.history().retentionHours());
    }

    @PostConstruct
    void warm() {
        HistoryConfig onDisk = store.load();
        if (onDisk == null) {
            cache.set(new HistoryConfig(defaultHours, 0L));
            log.info("HistoryConfigService seeded from yaml default: retentionHours={}", defaultHours);
        } else {
            cache.set(new HistoryConfig(HistoryConfig.clamp(onDisk.retentionHours()), onDisk.updatedAt()));
            log.info("HistoryConfigService loaded: retentionHours={}", cache.get().retentionHours());
        }
    }

    public HistoryConfig current() {
        return cache.get();
    }

    public synchronized HistoryConfig update(int retentionHours) {
        int clamped = HistoryConfig.clamp(retentionHours);
        HistoryConfig next = new HistoryConfig(clamped, System.currentTimeMillis());
        store.save(next);
        cache.set(next);
        log.info("HistoryConfig updated: retentionHours={}", clamped);
        return next;
    }
}
