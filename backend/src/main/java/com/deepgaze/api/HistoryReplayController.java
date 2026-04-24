package com.deepgaze.api;

import com.deepgaze.history.HistoryStore;
import com.deepgaze.model.MetricSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * V9 Time Machine read surface. Two narrow endpoints back the "Timeline
 * Scrubber" UI:
 *
 *   GET /api/history/range?targetId=X           — {min,max} epochMs bounds
 *                                                 available for the target
 *                                                 (used to size the slider)
 *   GET /api/history/replay?targetId=X&timestampMs=T
 *                                               — for every metricGroup the
 *                                                 target has emitted, the
 *                                                 snapshot whose ts is the
 *                                                 latest at or before T.
 *
 * Both run on boundedElastic because the SQLite read is blocking. The
 * controller is only wired when the {@link HistoryStore} bean exists, which
 * mirrors {@code deepgaze.history.enabled} — flipping that off disables the
 * endpoints rather than 500-ing.
 */
@RestController
@RequestMapping("/api/history")
@ConditionalOnBean(HistoryStore.class)
public class HistoryReplayController {

    private final HistoryStore store;

    public HistoryReplayController(HistoryStore store) {
        this.store = store;
    }

    @GetMapping("/range")
    public Mono<ResponseEntity<RangeResponse>> range(@RequestParam("targetId") String targetId) {
        return Mono.fromCallable(() -> store.rangeBounds(targetId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(bounds -> {
                    if (bounds == null) {
                        return ResponseEntity.ok(new RangeResponse(targetId, 0L, 0L, false));
                    }
                    return ResponseEntity.ok(new RangeResponse(targetId, bounds[0], bounds[1], true));
                });
    }

    @GetMapping("/replay")
    public Mono<ResponseEntity<ReplayResponse>> replay(
            @RequestParam("targetId") String targetId,
            @RequestParam("timestampMs") long timestampMs) {
        return Mono.fromCallable(() -> {
                    long[] bounds = store.rangeBounds(targetId);
                    List<MetricSnapshot> snaps = store.asOfSnapshot(targetId, timestampMs);
                    long minMs = bounds == null ? 0L : bounds[0];
                    long maxMs = bounds == null ? 0L : bounds[1];
                    return new ReplayResponse(targetId, timestampMs, minMs, maxMs, snaps);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    public record RangeResponse(String targetId, long rangeMinMs, long rangeMaxMs, boolean hasData) {}

    public record ReplayResponse(
            String targetId,
            long asOfMs,
            long rangeMinMs,
            long rangeMaxMs,
            List<MetricSnapshot> snapshots
    ) {}
}
