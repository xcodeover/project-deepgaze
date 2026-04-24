package com.deepgaze.notifications.api;

import com.deepgaze.notifications.NotificationConfig;
import com.deepgaze.notifications.NotificationConfigService;
import com.deepgaze.notifications.TelegramNotifier;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST surface for the unified Settings → Notifications panel.
 *
 *   GET  /api/settings/notifications        current config (bot token masked)
 *   PUT  /api/settings/notifications        upsert (null token = keep existing)
 *   POST /api/settings/notifications/test   one-shot probe via TelegramNotifier
 *
 * The bot token is WRITE-ONLY over the wire — the response exposes
 * {@code telegramBotTokenSet} and a masked {@code telegramBotTokenPreview}
 * ("…9XyZ") so the UI can confirm something is stored without leaking the
 * secret back into the browser.
 */
@Slf4j
@RestController
@RequestMapping("/api/settings/notifications")
public class NotificationSettingsController {

    private final NotificationConfigService service;
    private final TelegramNotifier notifier;

    public NotificationSettingsController(NotificationConfigService service, TelegramNotifier notifier) {
        this.service = service;
        this.notifier = notifier;
    }

    @GetMapping
    public NotificationSettingsDto get() {
        return NotificationSettingsDto.forResponse(service.current());
    }

    @PutMapping
    public NotificationSettingsDto update(@RequestBody NotificationSettingsDto body) {
        if (body == null) throw new IllegalArgumentException("Request body is required");
        NotificationConfig updated = service.update(body.toConfig());
        return NotificationSettingsDto.forResponse(updated);
    }

    @PostMapping("/test")
    public ResponseEntity<TelegramNotifier.TestResult> test(@RequestBody(required = false) TestRequest body) {
        String token  = body == null ? null : nullIfBlank(body.telegramBotToken());
        String chatId = body == null ? null : nullIfBlank(body.telegramChatId());
        TelegramNotifier.TestResult result = notifier.sendTest(token, chatId);
        return result.ok()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> onConflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /* ------------------------------------------------------------------ */
    /*   DTOs                                                              */
    /* ------------------------------------------------------------------ */

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NotificationSettingsDto(
            Boolean enabled,
            Boolean notifyOnCritical,
            Boolean notifyOnWarning,
            String telegramChatId,

            // On request: non-null means "replace", null means "unchanged".
            // On response: the decrypted plaintext token so operators can
            // see what's configured — disk storage stays AES-GCM encrypted,
            // and the endpoint itself is JWT-gated.
            String telegramBotToken,

            // Response-only diagnostics.
            Boolean telegramBotTokenSet,
            String  telegramBotTokenPreview,
            Long    updatedAt
    ) {
        public static NotificationSettingsDto forResponse(NotificationConfig cfg) {
            String token = cfg.telegramBotToken() == null ? "" : cfg.telegramBotToken();
            boolean set = !token.isBlank();
            String preview = set
                    ? "…" + token.substring(Math.max(0, token.length() - 4))
                    : null;
            return new NotificationSettingsDto(
                    cfg.enabled(),
                    cfg.notifyOnCritical(),
                    cfg.notifyOnWarning(),
                    cfg.telegramChatId() == null ? "" : cfg.telegramChatId(),
                    set ? token : null,
                    set,
                    preview,
                    cfg.updatedAt() == 0 ? null : cfg.updatedAt()
            );
        }

        /**
         * Translate the incoming request into a domain-layer config. A null
         * token field signals "keep existing" (the service honours the
         * sentinel); an empty string signals "clear it".
         */
        public NotificationConfig toConfig() {
            return new NotificationConfig(
                    enabled != null && enabled,
                    notifyOnCritical == null || notifyOnCritical,
                    notifyOnWarning  == null || notifyOnWarning,
                    telegramBotToken,    // null = sentinel for "unchanged"
                    telegramChatId == null ? "" : telegramChatId,
                    0L                   // service stamps updatedAt
            );
        }
    }

    public record TestRequest(String telegramBotToken, String telegramChatId) {}
}
