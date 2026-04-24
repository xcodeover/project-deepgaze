package com.deepgaze.targets.event;

import com.deepgaze.model.DbTargetConfig;

/**
 * Fired AFTER an existing target has been mutated. Subscribers that own
 * connection-sensitive resources (Hikari pools, SqlSessionFactory) should
 * tear down and rebuild on this event — connection fields may have changed.
 * Subscribers that only care about cosmetic fields ({@code displayName},
 * {@code displayOrder}) can read {@link #current()} directly.
 */
public record TargetUpdatedEvent(DbTargetConfig previous, DbTargetConfig current) {
    public String targetId() { return current.id(); }
}
