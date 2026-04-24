package com.deepgaze.history.api;

import com.deepgaze.history.HistoryConfig;
import com.deepgaze.history.HistoryConfigService;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Settings surface for the Time Machine retention knob.
 *
 *   GET /api/settings/history   current config + allowed bounds
 *   PUT /api/settings/history   upsert {retentionHours}
 *
 * The service clamps to {@link HistoryConfig#MIN_HOURS}..{@link HistoryConfig#MAX_HOURS}
 * server-side, so a malformed client request cannot drive the purge loop
 * into deleting everything or never purging.
 */
@Slf4j
@RestController
@RequestMapping("/api/settings/history")
public class HistorySettingsController {

    private final HistoryConfigService service;

    public HistorySettingsController(HistoryConfigService service) {
        this.service = service;
    }

    @GetMapping
    public HistorySettingsDto get() {
        return HistorySettingsDto.forResponse(service.current());
    }

    @PutMapping
    public HistorySettingsDto update(@RequestBody HistorySettingsDto body) {
        if (body == null || body.retentionHours == null) {
            throw new IllegalArgumentException("retentionHours is required");
        }
        HistoryConfig saved = service.update(body.retentionHours);
        return HistorySettingsDto.forResponse(saved);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistorySettingsDto(
            Integer retentionHours,
            Integer minHours,
            Integer maxHours,
            Long updatedAt
    ) {
        public static HistorySettingsDto forResponse(HistoryConfig cfg) {
            return new HistorySettingsDto(
                    cfg.retentionHours(),
                    HistoryConfig.MIN_HOURS,
                    HistoryConfig.MAX_HOURS,
                    cfg.updatedAt() == 0 ? null : cfg.updatedAt()
            );
        }
    }
}
