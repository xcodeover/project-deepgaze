package com.deepgaze.collector.slow;

import com.deepgaze.model.StorageSaturationDto;
import com.deepgaze.targets.event.TargetRemovedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last-wins in-memory cache of the most recent StorageSaturationDto per target,
 * kept deliberately separate from MetricRingBuffer.
 *
 * Storage saturation is low-frequency (60s) and pulled on demand by the REST
 * endpoint; putting it through the 1s SSE ring buffer would either spam empty
 * ticks to every subscriber or require a second stream. A dedicated cache
 * keeps the contract simple: latest value wins, and stale targets are evicted
 * when they leave the registry.
 *
 * Thread-safety: backed by {@link ConcurrentHashMap} so concurrent slow-tick
 * writes for different targets don't contend.
 */
@Slf4j
@Component
public class SaturationStore {

    private final Map<String, StorageSaturationDto> latest = new ConcurrentHashMap<>();

    public void put(StorageSaturationDto dto) {
        if (dto == null || dto.targetId() == null) return;
        latest.put(dto.targetId(), dto);
    }

    public Optional<StorageSaturationDto> get(String targetId) {
        return Optional.ofNullable(latest.get(targetId));
    }

    public Map<String, StorageSaturationDto> all() {
        // Defensive copy so callers can iterate freely without seeing tick-time
        // mutations mid-serialisation.
        return Map.copyOf(latest);
    }

    @EventListener
    public void onTargetRemoved(TargetRemovedEvent e) {
        StorageSaturationDto removed = latest.remove(e.targetId());
        if (removed != null) {
            log.info("Dropped storage saturation cache for removed target {}", e.targetId());
        }
    }
}
