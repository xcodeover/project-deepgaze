package com.deepgaze.notifications;

/**
 * In-memory shape of the notification settings persisted in the
 * {@code notification_config} SQLite table. Exactly one row exists (id=1) —
 * the table is effectively a typed key-value record, not a list, because the
 * product currently supports a single Telegram channel.
 *
 * {@code telegramBotToken} is stored AES-GCM-encrypted on disk (same cipher
 * as target passwords) and surfaces here as plaintext only inside the
 * backend. The REST layer never echoes the raw token — DTOs expose
 * {@code telegramBotTokenSet} and a masked preview instead.
 */
public record NotificationConfig(
        boolean enabled,
        boolean notifyOnCritical,
        boolean notifyOnWarning,
        String telegramBotToken,
        String telegramChatId,
        long updatedAt
) {
    public static NotificationConfig defaults() {
        return new NotificationConfig(false, true, true, "", "", 0L);
    }

    public boolean telegramConfigured() {
        return telegramBotToken != null && !telegramBotToken.isBlank()
            && telegramChatId   != null && !telegramChatId.isBlank();
    }

    public NotificationConfig withToken(String token) {
        return new NotificationConfig(enabled, notifyOnCritical, notifyOnWarning,
                token == null ? "" : token, telegramChatId, updatedAt);
    }
}
