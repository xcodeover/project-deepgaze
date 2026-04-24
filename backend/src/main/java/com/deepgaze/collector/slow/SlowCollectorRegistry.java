package com.deepgaze.collector.slow;

import com.deepgaze.model.DbType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Parallel to {@link com.deepgaze.collector.CollectorRegistry} — holds the
 * slow-queue SlowCollector beans indexed by the engine they support. Missing
 * engines are tolerated (Optional.empty) so the scheduler can skip targets
 * whose engine doesn't have a saturation implementation yet.
 */
@Slf4j
@Component
public class SlowCollectorRegistry {

    private final Map<DbType, SlowCollector> byType;

    public SlowCollectorRegistry(List<SlowCollector> collectors) {
        this.byType = collectors.stream()
                .collect(Collectors.toUnmodifiableMap(SlowCollector::supports, Function.identity()));
        log.info("SlowCollectorRegistry loaded with {} collector(s): {}", byType.size(), byType.keySet());
    }

    public Optional<SlowCollector> forType(DbType type) {
        return Optional.ofNullable(byType.get(type));
    }
}
