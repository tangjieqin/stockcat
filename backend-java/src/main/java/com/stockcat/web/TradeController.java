package com.stockcat.web;

import com.stockcat.service.TradeService;
import com.stockcat.web.dto.CreateTradeRequest;
import com.stockcat.web.dto.PageResponse;
import com.stockcat.web.dto.TradeResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.OffsetDateTime;

/**
 * 交易流水，对应 docs/API.md §6。
 */
@RestController
@RequestMapping("/api/v1")
public class TradeController {

    private final TradeService service;

    public TradeController(TradeService service) {
        this.service = service;
    }

    @GetMapping("/trades")
    public PageResponse<TradeResponse> list(
            @RequestParam(required = false) Long instrumentId,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(instrumentId, direction, from, to, page, size);
    }

    @PostMapping("/trades")
    public ResponseEntity<TradeResponse> create(@Valid @RequestBody CreateTradeRequest req) {
        TradeResponse created = service.create(req);
        return ResponseEntity.created(URI.create("/api/v1/trades/" + created.id())).body(created);
    }

    @DeleteMapping("/trades/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
