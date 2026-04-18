package com.deepgaze.collector;

import com.deepgaze.model.DbType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spring auto-discovers all Collector beans and we index them by the DbType they support.
 * Concrete collectors are added in Step 3 — this registry is intentionally tolerant of
 * "no collector for type" so the rest of the pipeline boots cleanly during development.
 */
@Slf4j
@Component
public class CollectorRegistry {

    private final Map<DbType, Collector> byType;

    public CollectorRegistry(List<Collector> collectors) {
        this.byType = collectors.stream()
                .collect(Collectors.toUnmodifiableMap(Collector::supports, Function.identity()));
        log.info("CollectorRegistry loaded with {} collector(s): {}", byType.size(), byType.keySet());
    }

    public Optional<Collector> forType(DbType type) {
        return Optional.ofNullable(byType.get(type));
    }
}
