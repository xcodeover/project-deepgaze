package com.deepgaze.targets;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;

/**
 * REST payload for target CRUD. Passwords are NEVER returned in responses;
 * {@code password} is input-only. On GET, the client receives
 * {@code passwordSet = true/false} so the UI can render a "********" masked
 * field and "leave blank to keep unchanged" semantics.
 *
 * Null fields on PUT mean "no change" so partial updates (renaming, reordering)
 * don't require echoing the full config back.
 */
public record TargetDto(
        String id,
        String displayName,
        Integer displayOrder,
        DbType engine,
        String jdbcUrl,
        String username,
        String password,
        Boolean passwordSet,
        Long pollIntervalMs,
        Boolean enabled,
        String hostExporterUrl,
        String opsUsername,
        String opsPassword,
        Boolean opsPasswordSet,
        Integer hikariMaxPoolSize,
        Integer hikariMinimumIdle,
        Long tcpConnectTimeoutMs,
        Long socketReadTimeoutMs
) {

    /**
     * Build a response DTO from the runtime config. Only active (enabled)
     * targets live in the registry, so {@code enabled=true} here. Passwords
     * are scrubbed; only presence flags are exposed.
     */
    public static TargetDto forResponse(DbTargetConfig cfg) {
        boolean pwd = cfg.password() != null && !cfg.password().isEmpty();
        boolean opsPwd = cfg.ops() != null && cfg.ops().password() != null && !cfg.ops().password().isEmpty();
        String opsUser = cfg.ops() == null ? "" : cfg.ops().username();
        return new TargetDto(
                cfg.id(),
                cfg.name(),
                cfg.displayOrder(),
                cfg.type(),
                cfg.jdbcUrl(),
                cfg.username(),
                null,
                pwd,
                cfg.pollIntervalMs(),
                true,
                cfg.hostExporterUrl(),
                opsUser,
                null,
                opsPwd,
                cfg.hikari().maximumPoolSize(),
                cfg.hikari().minimumIdle(),
                cfg.network().tcpConnectTimeoutMs(),
                cfg.network().socketReadTimeoutMs()
        );
    }

    /**
     * Build a response DTO directly from a persisted row — the only way to
     * expose disabled targets to the UI, since the registry cache only holds
     * enabled ones. Passwords are never decrypted here; we just flag whether
     * the ciphertext column is non-empty.
     */
    public static TargetDto forResponse(TargetRow row) {
        DbType engine;
        try {
            engine = DbType.valueOf(row.engine());
        } catch (IllegalArgumentException e) {
            engine = null;
        }
        boolean pwd = row.passwordEnc() != null && !row.passwordEnc().isEmpty();
        boolean opsPwd = row.opsPasswordEnc() != null && !row.opsPasswordEnc().isEmpty();
        return new TargetDto(
                row.id(),
                row.displayName(),
                row.displayOrder(),
                engine,
                row.jdbcUrl(),
                row.username(),
                null,
                pwd,
                row.pollIntervalMs(),
                row.enabled(),
                row.hostExporterUrl() == null ? "" : row.hostExporterUrl(),
                row.opsUsername() == null ? "" : row.opsUsername(),
                null,
                opsPwd,
                row.hikariMaxPoolSize(),
                row.hikariMinimumIdle(),
                row.tcpConnectTimeoutMs(),
                row.socketReadTimeoutMs()
        );
    }
}
