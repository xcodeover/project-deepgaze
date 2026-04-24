package com.deepgaze.api;

import com.deepgaze.session.ExplainResult;
import com.deepgaze.session.SessionDetailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * Dashboard-side explain entrypoint. The frontend forwards the raw table row
 * (topDigests / slowQueries / processlist), so we extract the SQL from
 * whichever field carries it and delegate to the already-complete
 * {@link SessionDetailService#explain} — that service handles the truncation,
 * prepared-placeholder, and root-cause-unwrapping edge cases and returns a
 * typed {@link ExplainResult}.
 *
 * MariaDB executes {@code EXPLAIN FORMAT=JSON} on the target. Oracle / MSSQL
 * still bubble up as {@code UNSUPPORTED} until their per-engine SQL is wired,
 * but the response shape matches so the UI switches on the typed status
 * rather than HTTP code.
 */
@RestController
public class ExplainController {

    private final SessionDetailService service;

    public ExplainController(SessionDetailService service) {
        this.service = service;
    }

    @PostMapping("/api/targets/{targetId}/explain")
    public Mono<ResponseEntity<ExplainResult>> explain(
            @PathVariable String targetId,
            @RequestBody(required = false) Map<String, Object> body) {
        String sql = extractSql(body);
        return Mono.fromCallable(() -> service.explain(targetId, sql))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok)
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.badRequest().build()));
    }

    /**
     * SQL may arrive under several keys depending on which table invoked the
     * modal: the Query Performance tiles carry {@code digest_text}, the
     * Processlist carries {@code info}, and a direct caller may pass
     * {@code sql}. First non-blank wins.
     */
    private static String extractSql(Map<String, Object> body) {
        if (body == null) return null;
        for (String key : new String[] { "sql", "digest_text", "info", "query", "DIGEST_TEXT", "INFO" }) {
            Object v = body.get(key);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return null;
    }
}
