package com.stockcat.web;

import com.stockcat.service.MonitorTargetService;
import com.stockcat.web.dto.CreateMonitorTargetRequest;
import com.stockcat.web.dto.MonitorTargetResponse;
import com.stockcat.web.dto.UpdateMonitorTargetRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * 监控标的 CRUD，对应 docs/API.md §2（与自选列表相互独立）。
 */
@RestController
@RequestMapping("/api/v1")
public class MonitorTargetController {

    private final MonitorTargetService service;

    public MonitorTargetController(MonitorTargetService service) {
        this.service = service;
    }

    @GetMapping("/monitor-targets")
    public List<MonitorTargetResponse> list() {
        return service.list();
    }

    @PostMapping("/monitor-targets")
    public ResponseEntity<MonitorTargetResponse> create(@Valid @RequestBody CreateMonitorTargetRequest req) {
        MonitorTargetResponse created = service.add(req);
        return ResponseEntity.created(URI.create("/api/v1/monitor-targets/" + created.id())).body(created);
    }

    @PatchMapping("/monitor-targets/{id}")
    public MonitorTargetResponse update(@PathVariable Long id,
                                        @Valid @RequestBody UpdateMonitorTargetRequest req) {
        return service.updateEnabled(id, req.enabled());
    }

    @DeleteMapping("/monitor-targets/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
