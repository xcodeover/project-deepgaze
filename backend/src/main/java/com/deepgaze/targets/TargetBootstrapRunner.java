package com.deepgaze.targets;

import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.DbTargetConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * One-way migration shim: on startup, if the {@code target} table is empty
 * AND {@code deepgaze.targets[]} in application.yml is non-empty, seed the
 * yaml entries into SQLite. Idempotent — once any row exists in the table
 * yaml is ignored for target definitions.
 *
 * Runs AFTER {@link TargetRegistry} so {@link TargetStore} is already
 * initialised, and we can reuse the registry's row-mapper + cipher.
 *
 * Bean-ordering contract: this runner declares @DependsOn("targetRegistry")
 * so the registry bean (and therefore {@link TargetStore}) is fully init'd
 * before we seed. Downstream pool registries in turn declare
 * @DependsOn("targetBootstrapRunner") so their @PostConstruct only runs
 * AFTER any yaml → DB migration has completed and the cache has been
 * reloaded with the seed rows.
 */
@Slf4j
@Component
@DependsOn({ "targetRegistry" })
public class TargetBootstrapRunner {

    private final DeepGazeProperties props;
    private final TargetStore store;
    private final TargetRegistry registry;

    public TargetBootstrapRunner(DeepGazeProperties props,
                                 TargetStore store,
                                 TargetRegistry registry) {
        this.props = props;
        this.store = store;
        this.registry = registry;
    }

    @PostConstruct
    public void seedIfEmpty() {
        int existing = store.count();
        if (existing > 0) {
            log.info("TargetBootstrapRunner: SQLite already has {} target(s) — ignoring yaml.", existing);
            return;
        }
        List<DbTargetConfig> yamlTargets = props.targets();
        if (yamlTargets == null || yamlTargets.isEmpty()) {
            log.info("TargetBootstrapRunner: no yaml targets to seed — start fresh via the Settings UI.");
            return;
        }
        log.info("TargetBootstrapRunner: seeding {} yaml target(s) into SQLite (one-way migration)…",
                yamlTargets.size());
        long now = System.currentTimeMillis();
        int order = 0;
        for (DbTargetConfig yaml : yamlTargets) {
            DbTargetConfig seeded = yaml.displayOrder() == 0
                    ? withOrder(yaml, order)
                    : yaml;
            try {
                store.insert(registry.toRow(seeded, now));
                log.info("  · seeded target id={} type={} order={}", seeded.id(), seeded.type(), seeded.displayOrder());
            } catch (Exception e) {
                log.error("  · failed to seed target id={}: {}", seeded.id(), e.getMessage());
            }
            order++;
        }
        // Re-hydrate so subsequent dependents (DataSourceRegistry @PostConstruct) see the seed rows.
        registry.reloadFromStore();
    }

    private static DbTargetConfig withOrder(DbTargetConfig c, int order) {
        return new DbTargetConfig(
                c.id(), c.name(), c.type(), c.jdbcUrl(), c.username(), c.password(),
                c.pollIntervalMs(), order, c.hikari(), c.network(), c.hostExporterUrl(), c.ops()
        );
    }
}
