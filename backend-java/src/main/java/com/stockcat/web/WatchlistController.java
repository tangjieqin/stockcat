package com.stockcat.web;

import com.stockcat.service.WatchlistService;
import com.stockcat.web.dto.CreateWatchlistRequest;
import com.stockcat.web.dto.MoveWatchlistGroupRequest;
import com.stockcat.web.dto.WatchlistItemResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * 自选列表，对应 docs/API.md §2。V7 起支持自定义分组。
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

    /** 自定义分组名列表（不含默认「自选」）。 */
    @GetMapping("/watchlist/groups")
    public List<String> groups() {
        return service.groups();
    }

    @PostMapping("/watchlist")
    public ResponseEntity<WatchlistItemResponse> add(@Valid @RequestBody CreateWatchlistRequest req) {
        WatchlistItemResponse created = service.add(req);
        return ResponseEntity.created(URI.create("/api/v1/watchlist/" + created.instrument().id())).body(created);
    }

    /** 移动到分组（groupName 为 null/空白 = 移回默认「自选」）。 */
    @PatchMapping("/watchlist/{instrumentId}/group")
    public WatchlistItemResponse moveToGroup(@PathVariable Long instrumentId,
                                             @Valid @RequestBody MoveWatchlistGroupRequest req) {
        return service.moveToGroup(instrumentId, req.groupName());
    }

    @DeleteMapping("/watchlist/{instrumentId}")
    public ResponseEntity<Void> delete(@PathVariable Long instrumentId) {
        service.delete(instrumentId);
        return ResponseEntity.noContent().build();
    }
}
