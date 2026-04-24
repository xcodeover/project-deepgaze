package com.deepgaze.security.api;

import com.deepgaze.security.UserRow;

/**
 * REST-facing user shape. Mirrors {@link UserRow} but scrubs the password
 * hash — the hash never leaves the backend. Used for both list and single
 * responses; password-write payloads use a separate request record.
 */
public record UserDto(
        long id,
        String username,
        String role,
        boolean enabled,
        long createdAt,
        long updatedAt
) {
    public static UserDto of(UserRow row) {
        return new UserDto(
                row.id(),
                row.username(),
                row.role(),
                row.enabled(),
                row.createdAt(),
                row.updatedAt()
        );
    }
}
