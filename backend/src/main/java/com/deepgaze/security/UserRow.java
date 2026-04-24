package com.deepgaze.security;

/**
 * SQLite-mapped shape of a row in the {@code users} table. Passwords are
 * always stored as BCrypt hashes — the plaintext never touches this record.
 *
 * {@link #role} is a single free-form string (ADMIN / VIEWER / OPERATOR
 * today) to keep the first cut simple; when role-based authorisation lands
 * we can expand this to a set without breaking on-disk compatibility.
 */
public record UserRow(
        long id,
        String username,
        String passwordHash,
        String role,
        boolean enabled,
        long createdAt,
        long updatedAt
) {}
