package com.deepgaze.alert;

import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.config.DeepGazeProperties.WebhookSinkConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Non-blocking HTTP delivery over Spring's WebClient. A failing webhook is
 * logged and swallowed — one broken receiver must not stop others from
 * delivering, and must never block the evaluator thread. Calls are fire-and-
 * forget: we subscribe but do not join.
 *
 * Registers as ONE NotificationSink bean and internally fans out to every
 * configured endpoint. This keeps bean wiring trivial (one LogSink bean + one
 * WebhookSink bean, both injected as `List<NotificationSink>` into the engine)
 * regardless of how many webhooks the operator configures.
 *
 * Per-endpoint shape is chosen by config: Telegram's {chat_id,text} when
 * `chatId` is set so the URL can point straight at the Bot API, otherwise
 * a generic envelope for Slack / Discord / custom receivers.
 */
@Slf4j
@Component
public class WebhookSink implements NotificationSink {

    private final List<Endpoint> endpoints = new ArrayList<>();

    public WebhookSink(DeepGazeProperties props, WebClient.Builder builder) {
        List<WebhookSinkConfig> cfgs = props.alerts().webhooks();
        if (cfgs == null) return;
        for (WebhookSinkConfig cfg : cfgs) {
            endpoints.add(new Endpoint(cfg, builder.baseUrl(cfg.url()).build()));
        }
    }

    @PostConstruct
    void announce() {
        if (endpoints.isEmpty()) {
            log.info("WebhookSink: no webhooks configured");
            return;
        }
        for (Endpoint e : endpoints) {
            log.info("WebhookSink[{}] → {} ({}{})",
                    e.cfg.id(), e.cfg.url(), e.cfg.method(),
                    e.cfg.chatId() != null ? ", telegram chat_id=" + e.cfg.chatId() : "");
        }
    }

    @Override
    public String id() {
        return "webhook";
    }

    @Override
    public void deliver(AlertEvent event) {
        for (Endpoint e : endpoints) {
            Map<String, Object> body = e.cfg.chatId() != null
                    ? telegramPayload(e.cfg, event)
                    : genericPayload(event);

            e.client.method(org.springframework.http.HttpMethod.valueOf(e.cfg.method()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofMillis(e.cfg.timeoutMs()))
                    .doOnError(err -> log.warn("WebhookSink[{}] delivery failed: {}", e.cfg.id(), err.toString()))
                    .onErrorResume(err -> Mono.empty())
                    .subscribe();
        }
    }

    private static Map<String, Object> telegramPayload(WebhookSinkConfig cfg, AlertEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", cfg.chatId());
        body.put("text", formatTelegramText(event));
        body.put("parse_mode", "HTML");
        return body;
    }

    private static Map<String, Object> genericPayload(AlertEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", event.kind().name());
        body.put("ruleId", event.ruleId());
        body.put("targetId", event.targetId());
        body.put("severity", event.severity().name());
        body.put("timestamp", event.timestamp().toString());
        body.put("value", event.value());
        body.put("message", event.message());
        return body;
    }

    private static String formatTelegramText(AlertEvent event) {
        String icon = switch (event.severity()) {
            case CRITICAL -> "\uD83D\uDD34";
            case WARNING  -> "\uD83D\uDFE1";
            case INFO     -> "\uD83D\uDD35";
        };
        String verb = event.kind() == AlertEvent.Kind.FIRED ? "FIRED" : "RESOLVED";
        return String.format(
                "%s <b>DeepGaze %s</b>%n<b>Rule:</b> %s%n<b>Target:</b> %s%n<b>Severity:</b> %s%n<b>Value:</b> %.4g%n%s",
                icon, verb, event.ruleId(), event.targetId(),
                event.severity().name(), event.value(), event.message()
        );
    }

    private record Endpoint(WebhookSinkConfig cfg, WebClient client) {}
}
