package com.deepgaze.targets;

/**
 * Raw SQLite row for the {@code target} table. Passwords are stored encrypted
 * (see {@link PasswordCipher}); conversion to the runtime {@link com.deepgaze.model.DbTargetConfig}
 * happens in {@link TargetRegistry} after decryption.
 */
public record TargetRow(
        String id,
        String displayName,
        int displayOrder,
        String engine,
        String jdbcUrl,
        String username,
        String passwordEnc,
        long pollIntervalMs,
        boolean enabled,
        String hostExporterUrl,
        String opsUsername,
        String opsPasswordEnc,
        int hikariMaxPoolSize,
        int hikariMinimumIdle,
        long tcpConnectTimeoutMs,
        long socketReadTimeoutMs,
        long createdAt,
        long updatedAt
) {}
