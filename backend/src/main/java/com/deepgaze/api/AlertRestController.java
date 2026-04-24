package com.deepgaze.api;

import com.deepgaze.alert.AlertRegistry;
import com.deepgaze.alert.AlertState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Current alert state — polled once on page load for backfill, then kept
 * up-to-date via the SSE stream. Depends only on the read-side port.
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertRestController {

    private final AlertRegistry registry;

    public AlertRestController(AlertRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<AlertState> all() {
        return registry.all();
    }

    @GetMapping("/firing")
    public List<AlertState> firing() {
        return registry.firing();
    }
}
