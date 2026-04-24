package com.deepgaze.queryhistory.api;

import com.deepgaze.queryhistory.QueryEvent;
import com.deepgaze.queryhistory.QueryEventStore;
import com.deepgaze.queryhistory.QueryEventStore.SearchQuery;
import com.deepgaze.queryhistory.QueryEventStore.SearchResult;
import com.deepgaze.queryhistory.QueryEventStore.TrendPoint;
import com.deepgaze.queryhistory.QueryEventStore.TrendQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only search surface for the per-target query-history table.
 *
 *   GET /api/query-history/search
 *     ?targetId=<id>&fromMs=<ms>&toMs=<ms>
 *     [&q=<substring|exact-digest>]
 *     [&source=topDigests|slowQueries]
 *     [&limit=100&offset=0]
 *
 * Latency fields in the response are engine-native: MySQL-family uses
 * picoseconds, MSSQL 100-ns ticks, Oracle microseconds. The UI is responsible
 * for formatting — the backend does not normalise so numbers round-trip with
 * the live dashboard.
 */
@Slf4j
@RestController
@RequestMapping("/api/query-history")
public class QueryHistoryController {

    private final QueryEventStore store;

    public QueryHistoryController(QueryEventStore store) {
        this.store = store;
    }

    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam("targetId")            String targetId,
            @RequestParam("fromMs")              long fromMs,
            @RequestParam("toMs")                long toMs,
            @RequestParam(value = "q",           required = false) String q,
            @RequestParam(value = "source",      required = false) String source,
            @RequestParam(value = "limit",       defaultValue = "100") int limit,
            @RequestParam(value = "offset",      defaultValue = "0")   int offset
    ) {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId is required");
        }
        if (toMs < fromMs) {
            throw new IllegalArgumentException("toMs must be >= fromMs");
        }
        SearchResult result = store.search(new SearchQuery(
                targetId.trim(),
                fromMs,
                toMs,
                q == null ? null : q.trim(),
                source,
                limit,
                offset
        ));
        return new SearchResponse(
                targetId,
                fromMs,
                toMs,
                result.total(),
                result.items().stream().map(QueryHistoryController::toDto).toList()
        );
    }

    /**
     * Batched digest-frequency trend for rendering sparklines next to each row
     * in the results table. The client submits the digests visible on the
     * current page and the same window used for the search.
     */
    @GetMapping("/trend")
    public TrendResponse trend(
            @RequestParam("targetId") String targetId,
            @RequestParam("fromMs")   long fromMs,
            @RequestParam("toMs")     long toMs,
            @RequestParam("digests")  List<String> digests,
            @RequestParam(value = "buckets", defaultValue = "24") int buckets
    ) {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId is required");
        }
        if (toMs < fromMs) {
            throw new IllegalArgumentException("toMs must be >= fromMs");
        }
        int b = Math.max(4, Math.min(buckets, 96));
        long span = Math.max(1L, toMs - fromMs);
        long bucketMs = Math.max(1_000L, span / b);

        List<TrendPoint> pts = store.trend(new TrendQuery(
                targetId.trim(), fromMs, toMs, bucketMs,
                digests == null ? List.of() : digests
        ));
        return new TrendResponse(targetId, fromMs, toMs, bucketMs, b, pts);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static QueryEventDto toDto(QueryEvent e) {
        return new QueryEventDto(
                e.tsEpochMs(),
                e.targetId(),
                e.engineType(),
                e.digest(),
                e.digestText(),
                e.countStar(),
                e.sumRowsSent(),
                e.sumRowsExamined(),
                e.avgTimerWait(),
                e.sumTimerWait(),
                e.sourceGroup()
        );
    }

    public record SearchResponse(
            String targetId,
            long fromMs,
            long toMs,
            long total,
            List<QueryEventDto> items
    ) {}

    public record TrendResponse(
            String targetId,
            long fromMs,
            long toMs,
            long bucketMs,
            int  buckets,
            List<TrendPoint> points
    ) {}

    public record QueryEventDto(
            long   tsEpochMs,
            String targetId,
            String engineType,
            String digest,
            String digestText,
            Long   countStar,
            Long   sumRowsSent,
            Long   sumRowsExamined,
            Double avgTimerWait,
            Double sumTimerWait,
            String sourceGroup
    ) {}
}
