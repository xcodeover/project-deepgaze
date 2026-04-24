package com.deepgaze.alert;

import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.MetricSnapshot;
import com.deepgaze.stream.MetricStream;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The alert evaluator. One Spring bean owns:
 *   1. the subscription to MetricStream — rules are evaluated on every
 *      inbound snapshot whose group matches
 *   2. the (ruleId, targetId) → AlertState map — the current state machine
 *   3. the transition event sink — wired to NotificationSinks and to the
 *      /api/stream/alerts SSE endpoint
 *
 * State machine (per rule-instance):
 *
 *                       predicate true                 predicate true AND
 *                                                      (now - pendingSince) >= for
 *   OK  ──────────►  PENDING  ───────────────────────────►  FIRING
 *    ▲                  │                                     │
 *    │                  │ predicate false                     │ predicate false
 *    └──────────────────┴─────────────────────────────────────┘
 *
 * FIRED events are emitted on PENDING→FIRING or direct OK→FIRING
 * (when for=0). RESOLVED events are emitted on FIRING→OK.
 * OK→PENDING and PENDING→OK are silent — they're just internal dampening.
 */
@Slf4j
@Component
public class AlertEngine implements AlertRegistry {

    private static final Sinks.EmitFailureHandler RETRY_BRIEFLY =
            Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(10));

    private final MetricStream stream;
    private final List<AlertRule> rules;
    private final long defaultForSeconds;
    private final List<NotificationSink> sinks;

    // Key format "<ruleId>::<targetId>" — a rule with target=null creates
    // one entry per target we see, a rule with target set creates exactly one.
    private final Map<String, AlertState> states = new ConcurrentHashMap<>();

    private final Sinks.Many<AlertEvent> events = Sinks.many().multicast().directBestEffort();
    private Disposable subscription;

    public AlertEngine(
            DeepGazeProperties props,
            MetricStream stream,
            List<NotificationSink> sinks
    ) {
        this.stream = stream;
        this.rules = props.alerts().rules() == null ? List.of() : List.copyOf(props.alerts().rules());
        this.defaultForSeconds = props.alerts().defaultForSeconds();
        this.sinks = sinks;
        log.info("AlertEngine configured with {} rule(s), {} sink(s), default-for={}s",
                rules.size(), sinks.size(), defaultForSeconds);
    }

    @PostConstruct
    void subscribe() {
        if (rules.isEmpty()) {
            log.info("AlertEngine: no rules — skipping MetricStream subscription");
            return;
        }
        this.subscription = stream.stream()
                .publishOn(Schedulers.boundedElastic())
                .subscribe(this::evaluate, err -> log.error("AlertEngine subscription error", err));
        log.info("AlertEngine subscribed to MetricStream");
    }

    @PreDestroy
    void unsubscribe() {
        if (subscription != null) subscription.dispose();
    }

    /* ------------------------------------------------------------------ */
    /*   Evaluation                                                        */
    /* ------------------------------------------------------------------ */

    private void evaluate(MetricSnapshot snap) {
        for (AlertRule rule : rules) {
            if (!rule.group().equals(snap.metricGroup())) continue;
            if (rule.target() != null && !rule.target().equals(snap.targetId())) continue;

            OptionalDouble observed = MetricExtractor.extract(rule, snap);
            if (observed.isEmpty()) continue;

            step(rule, snap.targetId(), observed.getAsDouble(), snap.timestamp());
        }
    }

    private void step(AlertRule rule, String targetId, double value, Instant now) {
        String key = rule.id() + "::" + targetId;
        boolean breached = rule.matches(value);
        long forSec = rule.forSeconds() != null ? rule.forSeconds() : defaultForSeconds;

        AlertState prev = states.get(key);
        AlertStatus prevStatus = prev == null ? AlertStatus.OK : prev.status();
        AlertStatus nextStatus = prevStatus;

        switch (prevStatus) {
            case OK -> {
                if (breached) nextStatus = forSec <= 0 ? AlertStatus.FIRING : AlertStatus.PENDING;
            }
            case PENDING -> {
                if (!breached) {
                    nextStatus = AlertStatus.OK;
                } else {
                    long pendingSince = prev.since().getEpochSecond();
                    if (now.getEpochSecond() - pendingSince >= forSec) {
                        nextStatus = AlertStatus.FIRING;
                    }
                }
            }
            case FIRING -> {
                if (!breached) nextStatus = AlertStatus.OK;
            }
        }

        Instant since = (nextStatus == prevStatus && prev != null) ? prev.since() : now;
        String message = renderMessage(rule, value);
        AlertState state = new AlertState(
                rule.id(), targetId, rule.severity(), nextStatus, since, now, value, message);
        states.put(key, state);

        if (prevStatus != AlertStatus.FIRING && nextStatus == AlertStatus.FIRING) {
            emit(new AlertEvent(AlertEvent.Kind.FIRED, rule.id(), targetId,
                    rule.severity(), now, value, message));
        } else if (prevStatus == AlertStatus.FIRING && nextStatus == AlertStatus.OK) {
            emit(new AlertEvent(AlertEvent.Kind.RESOLVED, rule.id(), targetId,
                    rule.severity(), now, value, message));
        }
    }

    private static String renderMessage(AlertRule rule, double value) {
        String subject = rule.isCountRule()
                ? rule.group() + " row count"
                : rule.metric();
        String prefix = rule.description() != null && !rule.description().isBlank()
                ? rule.description() + " — "
                : "";
        return String.format(Locale.ROOT, "%s%s=%.4g %s %.4g",
                prefix, subject, value, rule.op().name().toLowerCase(Locale.ROOT), rule.threshold());
    }

    private void emit(AlertEvent event) {
        log.info("AlertEvent: kind={} rule={} target={} severity={} msg=\"{}\"",
                event.kind(), event.ruleId(), event.targetId(), event.severity(), event.message());
        try {
            events.emitNext(event, RETRY_BRIEFLY);
        } catch (Exception e) {
            log.warn("AlertEngine event emit failed: {}", e.toString());
        }
        for (NotificationSink sink : sinks) {
            try {
                sink.deliver(event);
            } catch (Exception e) {
                log.warn("Sink {} failed for rule={} kind={}: {}",
                        sink.id(), event.ruleId(), event.kind(), e.toString());
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*   AlertRegistry                                                     */
    /* ------------------------------------------------------------------ */

    @Override
    public List<AlertState> all() {
        return new ArrayList<>(states.values());
    }

    @Override
    public List<AlertState> firing() {
        List<AlertState> out = new ArrayList<>();
        for (AlertState s : states.values()) {
            if (s.status() == AlertStatus.FIRING) out.add(s);
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public Flux<AlertEvent> events() {
        return events.asFlux();
    }
}
