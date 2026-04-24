package com.deepgaze.notifications;

import com.deepgaze.targets.PasswordCipher;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Read-through cache on top of {@link NotificationConfigStore}. Keeps the
 * current (plaintext) {@link NotificationConfig} in an {@link AtomicReference}
 * so the alert hot-path ({@link TelegramNotifier#deliver}) reads a single
 * volatile reference instead of hitting SQLite on every event.
 *
 * {@link #update(NotificationConfig)} is the single write path — it encrypts
 * the bot token, persists via the store, and then swaps the cache. The next
 * alert uses the new config with no restart, which is the core hot-reload
 * requirement of the settings page.
 *
 * Sentinel {@code KEEP_TOKEN} (null bot token) lets the REST layer say "don't
 * change the stored token" — matching the same pattern targets use for
 * passwords on PUT.
 */
@Slf4j
@Service
public class NotificationConfigService {

    public static final String KEEP_TOKEN_SENTINEL = null;

    private final NotificationConfigStore store;
    private final PasswordCipher cipher;
    private final AtomicReference<NotificationConfig> cache = new AtomicReference<>(NotificationConfig.defaults());

    public NotificationConfigService(NotificationConfigStore store, PasswordCipher cipher) {
        this.store = store;
        this.cipher = cipher;
    }

    @PostConstruct
    void warm() {
        NotificationConfig onDisk = store.load();
        String plaintext = decryptOrEmpty(onDisk.telegramBotToken());
        cache.set(new NotificationConfig(
                onDisk.enabled(),
                onDisk.notifyOnCritical(),
                onDisk.notifyOnWarning(),
                plaintext,
                onDisk.telegramChatId(),
                onDisk.updatedAt()
        ));
        log.info("NotificationConfigService loaded: enabled={}, telegramConfigured={}",
                cache.get().enabled(), cache.get().telegramConfigured());
    }

    /** Snapshot of the current config — plaintext token, safe to read on hot path. */
    public NotificationConfig current() {
        return cache.get();
    }

    /**
     * Persist + hot-swap. If {@code incoming.telegramBotToken()} is null,
     * keeps the existing stored token — matches the "password unchanged" UX
     * pattern so the frontend can PUT without re-sending a token the user
     * never retyped.
     */
    public synchronized NotificationConfig update(NotificationConfig incoming) {
        NotificationConfig prev = cache.get();
        String plaintextToken = incoming.telegramBotToken() == KEEP_TOKEN_SENTINEL
                ? prev.telegramBotToken()
                : incoming.telegramBotToken();

        long now = System.currentTimeMillis();
        NotificationConfig toPersist = new NotificationConfig(
                incoming.enabled(),
                incoming.notifyOnCritical(),
                incoming.notifyOnWarning(),
                encryptOrEmpty(plaintextToken),
                incoming.telegramChatId() == null ? "" : incoming.telegramChatId().trim(),
                now
        );
        store.save(toPersist);

        NotificationConfig swapped = new NotificationConfig(
                toPersist.enabled(),
                toPersist.notifyOnCritical(),
                toPersist.notifyOnWarning(),
                plaintextToken == null ? "" : plaintextToken,
                toPersist.telegramChatId(),
                now
        );
        cache.set(swapped);
        log.info("NotificationConfig updated: enabled={}, telegramConfigured={}",
                swapped.enabled(), swapped.telegramConfigured());
        return swapped;
    }

    private String encryptOrEmpty(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return "";
        if (!cipher.isConfigured()) {
            throw new IllegalStateException(
                    "DEEPGAZE_SECRET_KEY is not configured — cannot save bot token. " +
                    "Set the env var (or deepgaze.security.encryption-key) and restart.");
        }
        return cipher.encrypt(plaintext);
    }

    private String decryptOrEmpty(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        try {
            return cipher.decrypt(stored);
        } catch (Exception e) {
            log.warn("Stored bot token could not be decrypted — treating as absent: {}", e.getMessage());
            return "";
        }
    }
}
