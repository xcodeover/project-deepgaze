package com.deepgaze.api;

import com.deepgaze.model.MetricSnapshot;
import com.deepgaze.queue.MetricBufferReader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Polling REST endpoint serving the in-memory rolling history. This is the
 * surface that future Python AI consumers will fetch from. Depends only on
 * the read-side port (MetricBufferReader); the concrete buffer implementation
 * stays hidden.
 */
@RestController
@RequestMapping("/api/buffer")
public class MetricBufferRestController {

    private final MetricBufferReader buffer;

    public MetricBufferRestController(MetricBufferReader buffer) {
        this.buffer = buffer;
    }

    @GetMapping
    public List<MetricSnapshot> all() {
        return buffer.snapshot();
    }

    @GetMapping(params = "since")
    public List<MetricSnapshot> since(@RequestParam("since") Instant since) {
        return buffer.since(since);
    }

    @GetMapping("/info")
    public BufferStats info() {
        return new BufferStats(
                buffer.capacity(),
                buffer.size(),
                buffer.accepted(),
                buffer.evicted()
        );
    }

    public record BufferStats(int capacity, int size, long accepted, long evicted) {}
}
