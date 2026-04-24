package com.deepgaze.model;

import org.springframework.boot.context.properties.bind.DefaultValue;

public record DbTargetConfig(
        String id,
        /**
         * Human-readable label shown in the frontend target selector. Blank
         * falls back to {@link #id} so existing YAML without a name field
         * keeps working. Use {@link #displayName()} rather than reading this
         * field directly.
         */
        @DefaultValue("") String name,
        DbType type,
        String jdbcUrl,
        String username,
        String password,
        @DefaultValue("1000") long pollIntervalMs,
        /**
         * Fleet-View / Sidebar sort key. Lowest first, ties broken by id.
         * Bound via @DefaultValue so yaml-seeded targets without an explicit
         * value take 0. Mutable at runtime through the Settings UI.
         */
        @DefaultValue("0") int displayOrder,
        @DefaultValue HikariSettings hikari,
        @DefaultValue NetworkSettings network,
        /**
         * When set, the Infrastructure Hub's host/disk/memory panels are
         * populated by scraping this Prometheus text endpoint (typically
         * node_exporter on the DB host) instead of OSHI on the backend JVM.
         * Blank = fall back to local OSHI via HostMetricsCollector.
         */
        @DefaultValue("") String hostExporterUrl,
        /**
         * Separate DB credential used ONLY by KillCommandService. Keep the
         * monitoring credential read-only; grant CONNECTION_ADMIN (MariaDB)
         * / ALTER ANY CONNECTION (MSSQL) / ALTER SYSTEM (Oracle) to this
         * user only. Leave blank to disable the Kill Session feature.
         */
        @DefaultValue OpsSettings ops
) {
    public boolean hasHostExporter() {
        return hostExporterUrl != null && !hostExporterUrl.isBlank();
    }

    public String displayName() {
        return (name != null && !name.isBlank()) ? name : id;
    }

    public boolean hasOps() {
        return ops != null && ops.isConfigured();
    }

    /**
     * Credentials for the dedicated ops pool used by KillCommandService.
     * The monitoring pool stays read-only; this one can mutate. Both fields
     * must be non-blank for the feature to be considered configured.
     */
    public record OpsSettings(
            @DefaultValue("") String username,
            @DefaultValue("") String password
    ) {
        public boolean isConfigured() {
            return username != null && !username.isBlank()
                && password != null && !password.isBlank();
        }
    }

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
