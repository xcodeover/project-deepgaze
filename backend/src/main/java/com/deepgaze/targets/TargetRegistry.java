package com.deepgaze.targets;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbTargetConfig.HikariSettings;
import com.deepgaze.model.DbTargetConfig.NetworkSettings;
import com.deepgaze.model.DbTargetConfig.OpsSettings;
import com.deepgaze.model.DbType;
import com.deepgaze.targets.event.TargetAddedEvent;
import com.deepgaze.targets.event.TargetRemovedEvent;
import com.deepgaze.targets.event.TargetUpdatedEvent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Runtime source of truth for target definitions. Loads rows from {@link TargetStore}
 * on startup (decrypting passwords via {@link PasswordCipher}) into an in-memory
 * cache keyed by target id, and publishes Spring events whenever the cache
 * mutates so downstream pool/factory/scheduler components can hot-reload.
 *
 * All callers that used to do {@code props.targets()} now do
 * {@link #all()}. The yaml {@code deepgaze.targets} list is consulted ONLY by
 * {@link TargetBootstrapRunner} on first boot to seed this registry.
 *
 * Thread-safety: the cache is a {@link ConcurrentMap}; mutations go through
 * {@code synchronized} methods so CRUD + event publication stay serialised
 * (two near-simultaneous deletes of the same id cannot double-publish a
 * remove event).
 */
@Slf4j
@Component
public class TargetRegistry {

    private final TargetStore store;
    private final PasswordCipher cipher;
    private final ApplicationEventPublisher events;

    /** id -> fully-hydrated (decrypted) DbTargetConfig. */
    private final ConcurrentMap<String, DbTargetConfig> byId = new ConcurrentHashMap<>();

    public TargetRegistry(TargetStore store,
                          PasswordCipher cipher,
                          ApplicationEventPublisher events) {
        this.store = store;
        this.cipher = cipher;
        this.events = events;
    }

    /**
     * Loads the SQLite rows into memory. Runs in {@code @PostConstruct} so by
     * the time DataSourceRegistry (which depends on this bean) initialises its
     * pools, the cache is already populated. Seeding from yaml happens in a
     * separate {@link TargetBootstrapRunner} that refreshes this registry
     * BEFORE DataSourceRegistry starts — see that class for the ordering trick.
     */
    @PostConstruct
    public synchronized void init() {
        reloadFromStore();
        log.info("TargetRegistry loaded {} target(s) from SQLite.", byId.size());
    }

    /** Re-reads all rows from the DB and replaces the in-memory cache. */
    public synchronized void reloadFromStore() {
        byId.clear();
        for (TargetRow row : store.findAll()) {
            if (!row.enabled()) {
                log.info("Target {} is disabled — skipping runtime load.", row.id());
                continue;
            }
            try {
                byId.put(row.id(), toConfig(row));
            } catch (Exception e) {
                log.error("Failed to hydrate target {}: {}", row.id(), e.getMessage());
            }
        }
    }

    /** Ordered by (displayOrder, id). Returns a snapshot — safe to iterate outside a lock. */
    public List<DbTargetConfig> all() {
        return byId.values().stream()
                .sorted(Comparator.comparingInt(DbTargetConfig::displayOrder)
                        .thenComparing(DbTargetConfig::id))
                .toList();
    }

    public Optional<DbTargetConfig> byId(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    public boolean contains(String id) { return id != null && byId.containsKey(id); }

    public Collection<String> ids() { return Collections.unmodifiableCollection(byId.keySet()); }

    /* =================================================================
     *                       Mutations (called by TargetService)
     * ================================================================= */

    /**
     * Adds a new target. Persists to SQLite first (so a crash before the event
     * fires leaves the DB consistent), then updates the cache, then publishes
     * {@link TargetAddedEvent}. Re-throws on duplicate id.
     */
    public synchronized DbTargetConfig add(DbTargetConfig cfg) {
        if (cfg == null) throw new IllegalArgumentException("target cannot be null");
        if (byId.containsKey(cfg.id())) {
            throw new IllegalArgumentException("Target already exists: " + cfg.id());
        }
        TargetRow row = toRow(cfg, System.currentTimeMillis());
        store.insert(row);
        byId.put(cfg.id(), cfg);
        log.info("Target added: id={} type={}", cfg.id(), cfg.type());
        events.publishEvent(new TargetAddedEvent(cfg));
        return cfg;
    }

    /**
     * Replaces an existing target. Publishes {@link TargetUpdatedEvent} carrying
     * both prior and current config so subscribers can decide whether a full
     * pool rebuild is required (connection fields differ) or a cheap update
     * suffices (only {@code displayName}/{@code displayOrder} changed).
     */
    public synchronized DbTargetConfig update(DbTargetConfig cfg) {
        if (cfg == null) throw new IllegalArgumentException("target cannot be null");
        DbTargetConfig prev = byId.get(cfg.id());
        if (prev == null) throw new IllegalArgumentException("Unknown target: " + cfg.id());

        long now = System.currentTimeMillis();
        TargetRow existing = store.findById(cfg.id());
        long createdAt = existing != null ? existing.createdAt() : now;
        TargetRow row = toRow(cfg, createdAt, now);
        store.update(row);
        byId.put(cfg.id(), cfg);
        log.info("Target updated: id={}", cfg.id());
        events.publishEvent(new TargetUpdatedEvent(prev, cfg));
        return cfg;
    }

    public synchronized void remove(String id) {
        DbTargetConfig prev = byId.remove(id);
        if (prev == null) throw new IllegalArgumentException("Unknown target: " + id);
        store.delete(id);
        log.info("Target removed: id={}", id);
        events.publishEvent(new TargetRemovedEvent(prev));
    }

    /**
     * Flip the {@code enabled} flag for a target without rewriting its
     * configuration. Enabling a previously disabled row hydrates it and
     * publishes {@link TargetAddedEvent} so pools/schedulers spin up;
     * disabling evicts from the cache and publishes {@link TargetRemovedEvent}
     * so those same resources tear down cleanly.
     */
    public synchronized void setEnabled(String id, boolean enabled) {
        TargetRow row = store.findById(id);
        if (row == null) throw new IllegalArgumentException("Unknown target: " + id);
        if (row.enabled() == enabled) return; // no-op — already in requested state
        store.updateEnabled(id, enabled);
        if (enabled) {
            DbTargetConfig cfg = toConfig(row);
            byId.put(id, cfg);
            log.info("Target enabled: id={}", id);
            events.publishEvent(new TargetAddedEvent(cfg));
        } else {
            DbTargetConfig prev = byId.remove(id);
            log.info("Target disabled: id={}", id);
            if (prev != null) events.publishEvent(new TargetRemovedEvent(prev));
        }
    }

    /**
     * All rows in the store, including disabled ones. Used by the admin UI
     * to render the settings table — the in-memory {@link #byId} cache only
     * holds enabled targets so we can't reuse {@link #all()} here.
     */
    public List<TargetRow> allRows() {
        return store.findAll();
    }

    /** Reorder without touching other fields. Publishes one {@link TargetUpdatedEvent} per changed id. */
    public synchronized void reorder(List<TargetStore.OrderUpdate> updates) {
        store.reorder(updates);
        for (TargetStore.OrderUpdate u : updates) {
            DbTargetConfig prev = byId.get(u.id());
            if (prev == null || prev.displayOrder() == u.displayOrder()) continue;
            DbTargetConfig next = withDisplayOrder(prev, u.displayOrder());
            byId.put(u.id(), next);
            events.publishEvent(new TargetUpdatedEvent(prev, next));
        }
        log.info("Reordered {} target(s)", updates.size());
    }

    /* =================================================================
     *                        Row <-> config mapping
     * ================================================================= */

    private DbTargetConfig toConfig(TargetRow r) {
        DbType type;
        try {
            type = DbType.valueOf(r.engine());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown engine '" + r.engine() + "' for target " + r.id());
        }
        String password = cipher.decrypt(r.passwordEnc());
        String opsPassword = cipher.decrypt(r.opsPasswordEnc());
        return new DbTargetConfig(
                r.id(),
                r.displayName(),
                type,
                r.jdbcUrl(),
                r.username(),
                password,
                r.pollIntervalMs(),
                r.displayOrder(),
                new HikariSettings(
                        Math.max(1, r.hikariMaxPoolSize()),
                        Math.max(0, r.hikariMinimumIdle()),
                        3_000L, 60_000L, 600_000L, 3_000L
                ),
                new NetworkSettings(r.tcpConnectTimeoutMs(), r.socketReadTimeoutMs()),
                r.hostExporterUrl() == null ? "" : r.hostExporterUrl(),
                new OpsSettings(
                        r.opsUsername() == null ? "" : r.opsUsername(),
                        opsPassword
                )
        );
    }

    /** Convenience for {@link #add(DbTargetConfig)} — createdAt == updatedAt. */
    TargetRow toRow(DbTargetConfig cfg, long now) { return toRow(cfg, now, now); }

    TargetRow toRow(DbTargetConfig cfg, long createdAt, long updatedAt) {
        String pwdEnc = cipher.encrypt(cfg.password() == null ? "" : cfg.password());
        String opsPwdEnc = (cfg.ops() == null || cfg.ops().password() == null)
                ? "" : cipher.encrypt(cfg.ops().password());
        return new TargetRow(
                cfg.id(),
                cfg.name() == null || cfg.name().isBlank() ? cfg.id() : cfg.name(),
                cfg.displayOrder(),
                cfg.type().name(),
                cfg.jdbcUrl(),
                cfg.username(),
                pwdEnc,
                cfg.pollIntervalMs(),
                true,
                cfg.hostExporterUrl() == null ? "" : cfg.hostExporterUrl(),
                cfg.ops() == null ? "" : (cfg.ops().username() == null ? "" : cfg.ops().username()),
                opsPwdEnc,
                cfg.hikari().maximumPoolSize(),
                cfg.hikari().minimumIdle(),
                cfg.network().tcpConnectTimeoutMs(),
                cfg.network().socketReadTimeoutMs(),
                createdAt,
                updatedAt
        );
    }

    private static DbTargetConfig withDisplayOrder(DbTargetConfig prev, int newOrder) {
        return new DbTargetConfig(
                prev.id(), prev.name(), prev.type(), prev.jdbcUrl(), prev.username(), prev.password(),
                prev.pollIntervalMs(), newOrder, prev.hikari(), prev.network(),
                prev.hostExporterUrl(), prev.ops()
        );
    }
}
