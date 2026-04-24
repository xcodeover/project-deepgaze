package com.deepgaze.api;

import com.deepgaze.session.ExplainResult;
import com.deepgaze.session.SessionDetail;
import com.deepgaze.session.SessionDetailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * On-demand endpoints for the Session Detail drawer. Both wrap the blocking
 * MyBatis call in Mono.fromCallable(...).subscribeOn(boundedElastic) so the
 * reactor event loop never blocks on JDBC — this is the standard WebFlux
 * bridge for legacy blocking clients.
 *
 * Error policy:
 *   - Unknown target / unsupported engine → 400.
 *   - Session disappeared between snapshot and click → 200 with found=false.
 *   - EXPLAIN failures are returned as 200 with a typed ExplainResult
 *     (REJECTED / FAILED) so the UI can render the reason inline rather
 *     than via a generic error toast.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionDetailController {

    private final SessionDetailService service;

    public SessionDetailController(SessionDetailService service) {
        this.service = service;
    }

    @GetMapping("/{targetId}/{pid}")
    public Mono<ResponseEntity<SessionDetail>> detail(
            @PathVariable String targetId,
            @PathVariable long pid) {
        return Mono.fromCallable(() -> service.fetchDetail(targetId, pid))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok)
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.badRequest().build()))
                .onErrorResume(UnsupportedOperationException.class,
                        e -> Mono.just(ResponseEntity.status(501).build()));
    }

    @PostMapping("/{targetId}/explain")
    public Mono<ResponseEntity<ExplainResult>> explain(
            @PathVariable String targetId,
            @RequestBody ExplainRequest body) {
        String sql = body == null ? null : body.sql();
        return Mono.fromCallable(() -> service.explain(targetId, sql))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok)
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.badRequest().build()));
    }

    public record ExplainRequest(String sql) {}
}
