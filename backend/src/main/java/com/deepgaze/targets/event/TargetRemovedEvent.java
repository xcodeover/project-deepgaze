package com.deepgaze.targets.event;

import com.deepgaze.model.DbTargetConfig;

/**
 * Fired AFTER a target has been removed from the registry and the SQLite row
 * has been deleted. Subscribers tear down all resources bound to the target
 * id — pools, mappers, scheduled ticks, per-target ring-buffer slices.
 */
public record TargetRemovedEvent(DbTargetConfig target) {
    public String targetId() { return target.id(); }
}
