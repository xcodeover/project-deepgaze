package com.deepgaze.ops;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Mutation boundary. Every write the dashboard can trigger goes through this
 * controller. Requests are already authenticated by {@link OpsAuthFilter}
 * before they reach here, so there's no per-method auth check.
 *
 * Blocking JDBC is bridged to Mono via {@code boundedElastic} — same pattern
 * as {@link com.deepgaze.api.SessionDetailController}. HTTP status codes map
 * to {@link KillCommandService.KillOutcome.Status}:
 *
 *   OK           → 200 + session row
 *   REJECTED     → 409 (e.g. protected user, session already gone)
 *   UNAVAILABLE  → 503 (target has no ops credential configured)
 *   FAILED       → 500 (JDBC / driver failure — look at server log)
 */
@RestController
@RequestMapping("/api/ops")
public class KillCommandController {

    private final KillCommandService service;

    public KillCommandController(KillCommandService service) {
        this.service = service;
    }

    @PostMapping("/targets/{targetId}/sessions/{threadId}/kill")
    public Mono<ResponseEntity<KillResponse>> kill(
            @PathVariable String targetId,
            @PathVariable long threadId) {
        return Mono.fromCallable(() -> service.kill(targetId, threadId, "operator"))
                .subscribeOn(Schedulers.boundedElastic())
                .map(KillCommandController::toResponse);
    }

    private static ResponseEntity<KillResponse> toResponse(KillCommandService.KillOutcome out) {
        KillResponse body = new KillResponse(out.status().name(), out.message(), out.row());
        return switch (out.status()) {
            case OK          -> ResponseEntity.ok(body);
            case REJECTED    -> ResponseEntity.status(409).body(body);
            case UNAVAILABLE -> ResponseEntity.status(503).body(body);
            case FAILED      -> ResponseEntity.status(500).body(body);
        };
    }

    public record KillResponse(String status, String message, KillCommandService.SessionRow session) {}
}
