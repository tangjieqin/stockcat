package com.stockcat.web;

import com.stockcat.service.WatchlistService;
import com.stockcat.web.dto.CreateWatchlistRequest;
import com.stockcat.web.dto.WatchlistItemResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * 自选列表，对应 docs/API.md §2。
 */
@RestController
@RequestMapping("/api/v1")
public class WatchlistController {

    private final WatchlistService service;

    public WatchlistController(WatchlistService service) {
        this.service = service;
    }

    @GetMapping("/watchlist")
    public List<WatchlistItemResponse> list() {
        return service.list();
    }

    @PostMapping("/watchlist")
    public ResponseEntity<WatchlistItemResponse> add(@Valid @RequestBody CreateWatchlistRequest req) {
        WatchlistItemResponse created = service.add(req);
        return ResponseEntity.created(URI.create("/api/v1/watchlist/" + created.instrument().id())).body(created);
    }

    @DeleteMapping("/watchlist/{instrumentId}")
    public ResponseEntity<Void> delete(@PathVariable Long instrumentId) {
        service.delete(instrumentId);
        return ResponseEntity.noContent().build();
    }
}
