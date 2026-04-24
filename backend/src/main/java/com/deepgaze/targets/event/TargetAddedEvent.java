package com.deepgaze.targets.event;

import com.deepgaze.model.DbTargetConfig;

/**
 * Fired AFTER {@link com.deepgaze.targets.TargetRegistry} has committed a new
 * target to the in-memory cache (and the SQLite row has been persisted).
 * Downstream subscribers — pool registries, MyBatis factory registry, collector
 * scheduler — build their resources in response. Payload carries the fully
 * hydrated {@link DbTargetConfig} (decrypted) so subscribers don't need a
 * round-trip to the registry.
 */
public record TargetAddedEvent(DbTargetConfig target) {}
