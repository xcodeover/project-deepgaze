package com.deepgaze.notifications;

import com.deepgaze.alert.AlertEvent;
import com.deepgaze.alert.AlertRule;
import com.deepgaze.alert.NotificationSink;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dynamic-config Telegram sink. Unlike {@link com.deepgaze.alert.WebhookSink}
 * which binds its endpoint list at construction time, this sink reads from
 * {@link NotificationConfigService#current()} on every delivery — so the
 * next alert after a PUT /api/settings/notifications uses the new bot
 * token / chat id / severity filters with no restart.
 *
 * Severity gating:
 *   enabled=false                → drop everything
 *   CRITICAL + notifyOnCritical  → deliver
 *   WARNING  + notifyOnWarning   → deliver
 *   INFO                         → always deliver (rare but useful)
 *
 * Delivery uses a single shared {@link WebClient} targeting the Telegram Bot
 * API root; the bot token is injected into the URL path per request so one
 * WebClient serves any token. Failures are logged and swallowed — an
 * invalid token must not stall the evaluator thread.
 */
@Slf4j
@Component
public class TelegramNotifier implements NotificationSink {

    private static final String BASE_URL = "https://api.telegram.org";
    private static final long TIMEOUT_MS = 5_000;

    private final NotificationConfigService service;
    private final WebClient client;

    public TelegramNotifier(NotificationConfigService service, WebClient.Builder builder) {
        this.service = service;
        this.client = builder.baseUrl(BASE_URL).build();
    }

    @PostConstruct
    void announce() {
        NotificationConfig cfg = service.current();
        log.info("TelegramNotifier registered: enabled={}, telegramConfigured={}",
                cfg.enabled(), cfg.telegramConfigured());
    }

    @Override
    public String id() {
        return "telegram";
    }

    @Override
    public void deliver(AlertEvent event) {
        NotificationConfig cfg = service.current();
        if (!shouldSend(cfg, event.severity())) return;
        if (!cfg.telegramConfigured()) {
            log.debug("TelegramNotifier: skipping delivery, telegram not configured");
            return;
        }

        String body = formatEvent(event);
        sendAsync(cfg.telegramBotToken(), cfg.telegramChatId(), body, "MarkdownV2")
                .doOnError(err -> log.warn("TelegramNotifier delivery failed: {}", err.toString()))
                .onErrorResume(err -> Mono.empty())
                .subscribe();
    }

    /**
     * Synchronous send used by the "Send Test Message" button — surfaces
     * the failure reason directly so the UI can toast it. {@code override}
     * carries whatever credentials the user has typed in the form so they
     * can test BEFORE saving (common admin-panel UX).
     */
    public TestResult sendTest(String botTokenOverride, String chatIdOverride) {
        NotificationConfig cfg = service.current();
        String token  = firstNonBlank(botTokenOverride, cfg.telegramBotToken());
        String chatId = firstNonBlank(chatIdOverride,   cfg.telegramChatId());
        if (token == null || chatId == null) {
            return TestResult.err("Bot Token and Chat ID are required to send a test message.");
        }

        String text = "*DeepGaze* test message\n"
                    + "If you can read this, your notification channel is wired up correctly\\.";
        long started = System.currentTimeMillis();
        try {
            sendAsync(token, chatId, text, "MarkdownV2")
                    .block(Duration.ofMillis(TIMEOUT_MS));
            long elapsed = System.currentTimeMillis() - started;
            return TestResult.ok("Message delivered in " + elapsed + " ms.");
        } catch (Exception e) {
            String reason = rootMessage(e);
            return TestResult.err("Telegram rejected the test message: " + reason);
        }
    }

    /* ------------------------------------------------------------------ */

    private Mono<Void> sendAsync(String botToken, String chatId, String text, String parseMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", parseMode);

        return client.post()
                .uri("/bot{token}/sendMessage", botToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .then();
    }

    private static boolean shouldSend(NotificationConfig cfg, AlertRule.Severity severity) {
        if (!cfg.enabled()) return false;
        return switch (severity) {
            case CRITICAL -> cfg.notifyOnCritical();
            case WARNING  -> cfg.notifyOnWarning();
            case INFO     -> true;
        };
    }

    private static String formatEvent(AlertEvent event) {
        String icon = switch (event.severity()) {
            case CRITICAL -> "\uD83D\uDD34";
            case WARNING  -> "\uD83D\uDFE1";
            case INFO     -> "\uD83D\uDD35";
        };
        String verb = event.kind() == AlertEvent.Kind.FIRED ? "FIRED" : "RESOLVED";
        return icon + " *DeepGaze " + verb + "*\n"
             + "*Rule:* "      + escapeMd(event.ruleId())   + "\n"
             + "*Target:* "    + escapeMd(event.targetId()) + "\n"
             + "*Severity:* "  + event.severity().name()    + "\n"
             + "*Value:* "     + escapeMd(String.format("%.4g", event.value())) + "\n"
             + escapeMd(event.message());
    }

    /** MarkdownV2 requires escaping these characters. */
    private static String escapeMd(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("_*[]()~`>#+-=|{}.!\\".indexOf(c) >= 0) out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName() : msg;
    }

    public record TestResult(boolean ok, String message) {
        public static TestResult ok(String m)  { return new TestResult(true, m); }
        public static TestResult err(String m) { return new TestResult(false, m); }
    }
}
