package com.deepgaze.targets.api;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.targets.TargetDto;
import com.deepgaze.targets.TargetService;
import com.deepgaze.targets.TargetStore;
import com.deepgaze.targets.TargetService.ReorderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Control-plane REST surface for dynamic target management.
 *
 *   GET    /api/targets                 list all (ordered by displayOrder, id)
 *   GET    /api/targets/{id}            single target (passwords masked)
 *   POST   /api/targets                 create
 *   PUT    /api/targets/{id}            full/partial update (null fields = no change)
 *   DELETE /api/targets/{id}            remove + tear down pools
 *   POST   /api/targets/reorder         bulk [{id, displayOrder}]
 *   POST   /api/targets/test-connection one-shot pool probe; target need not exist yet
 *
 * Password fields are WRITE-ONLY: {@code password} / {@code opsPassword}
 * accepted on POST/PUT; responses carry {@code passwordSet}/{@code opsPasswordSet}
 * booleans instead of the encrypted blob.
 */
@Slf4j
@RestController
@RequestMapping("/api/targets")
public class TargetController {

    private final TargetService service;

    public TargetController(TargetService service) {
        this.service = service;
    }

    @GetMapping
    public List<TargetDto> list() {
        // Row-based so the admin UI also sees disabled targets — the registry
        // cache holds only the active ones.
        return service.listAllRows().stream().map(TargetDto::forResponse).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<TargetDto> get(@PathVariable String id) {
        return service.get(id)
                .map(TargetDto::forResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public TargetDto create(@RequestBody TargetDto body) {
        DbTargetConfig created = service.create(body);
        return TargetDto.forResponse(created);
    }

    @PutMapping("/{id}")
    public TargetDto update(@PathVariable String id, @RequestBody TargetDto body) {
        DbTargetConfig updated = service.update(id, body);
        return TargetDto.forResponse(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/enabled")
    public ResponseEntity<Void> setEnabled(@PathVariable String id, @RequestBody EnabledRequest body) {
        boolean enabled = body != null && Boolean.TRUE.equals(body.enabled());
        service.setEnabled(id, enabled);
        return ResponseEntity.noContent().build();
    }

    public record EnabledRequest(Boolean enabled) {}

    @PostMapping("/reorder")
    public ResponseEntity<Void> reorder(@RequestBody ReorderRequest body) {
        List<TargetStore.OrderUpdate> updates = TargetService.toOrderUpdates(
                body == null ? null : body.entries());
        service.reorder(updates);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test-connection")
    public TargetService.TestResult testConnection(@RequestBody TargetDto body) {
        return service.testConnection(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
