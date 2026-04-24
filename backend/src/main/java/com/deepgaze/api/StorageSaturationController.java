package com.deepgaze.api;

import com.deepgaze.collector.slow.SaturationStore;
import com.deepgaze.model.StorageSaturationDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the most recent storage-saturation snapshot for a target from the
 * in-memory {@link SaturationStore}. Never issues a DB query on the request
 * thread — the slow queue owns collection, this controller only reads the
 * last-wins cache.
 *
 * 204 No Content is returned when no snapshot exists yet (target just added,
 * or engine has no SlowCollector registered); clients should treat it as
 * "loading" rather than an error.
 */
@RestController
@RequestMapping("/api/storage")
public class StorageSaturationController {

    private final SaturationStore store;

    public StorageSaturationController(SaturationStore store) {
        this.store = store;
    }

    @GetMapping("/saturation")
    public ResponseEntity<StorageSaturationDto> saturation(@RequestParam("targetId") String targetId) {
        return store.get(targetId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
