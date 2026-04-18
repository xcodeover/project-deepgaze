package com.deepgaze.model;

import org.springframework.boot.context.properties.bind.DefaultValue;

public record DbTargetConfig(
        String id,
        DbType type,
        String jdbcUrl,
        String username,
        String password,
        @DefaultValue("1000") long pollIntervalMs,
        @DefaultValue HikariSettings hikari,
        @DefaultValue NetworkSettings network
) {
    public record HikariSettings(
            @DefaultValue("4")     int maximumPoolSize,
            @DefaultValue("1")     int minimumIdle,
            @DefaultValue("3000")  long connectionTimeoutMs,
            @DefaultValue("60000") long idleTimeoutMs,
            @DefaultValue("600000") long maxLifetimeMs,
            @DefaultValue("3000")  long validationTimeoutMs
    ) {}

    /**
     * Driver-level socket / TCP timeouts. These are the ONLY defence against a
     * silently-dropped connection (firewall blackhole, NAT timeout) — neither
     * Hikari pool timeouts nor Statement.setQueryTimeout will fire when the
     * socket itself never returns. Applied as native driver properties by
     * HikariPoolFactory based on DbType.
     */
    public record NetworkSettings(
            @DefaultValue("5000") long tcpConnectTimeoutMs,   // TCP / login phase
            @DefaultValue("8000") long socketReadTimeoutMs    // SO_TIMEOUT — hard cap on any read
    ) {}
}
