package com.stockcat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.service.RecapService;
import com.stockcat.web.dto.PageResponse;
import com.stockcat.web.dto.RecapDtos.CreateRecapRequest;
import com.stockcat.web.dto.RecapDtos.UpdateRecapRequest;
import com.stockcat.web.dto.RecapResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;

/**
 * 市场复盘，对应 docs/API.md §7。
 */
@RestController
@RequestMapping("/api/v1")
public class RecapController {

    private final RecapService service;
    private final ObjectMapper objectMapper;

    public RecapController(RecapService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/recaps")
    public PageResponse<RecapResponse> list(
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<com.stockcat.domain.MarketRecap> recaps = service.list(date, from, to, keyword, page, size);
        return new PageResponse<>(recaps.items().stream().map(RecapResponse::from).toList(),
                recaps.page(), recaps.size(), recaps.total());
    }

    @GetMapping("/recaps/{id}")
    public RecapResponse get(@PathVariable Long id) {
        return RecapResponse.from(service.get(id));
    }

    @PostMapping("/recaps")
    public ResponseEntity<RecapResponse> create(@Valid @RequestBody CreateRecapRequest req) {
        var created = service.create(req.recapDate(), req.title(), req.marketSummary(),
                req.sectorNotes(), req.content(), toJson(req.relatedAlertEventIds()));
        return ResponseEntity.created(URI.create("/api/v1/recaps/" + created.getId()))
                .body(RecapResponse.from(created));
    }

    @PatchMapping("/recaps/{id}")
    public RecapResponse update(@PathVariable Long id, @RequestBody UpdateRecapRequest req) {
        return RecapResponse.from(service.update(id, req.title(), req.marketSummary(),
                req.sectorNotes(), req.content(), toJson(req.relatedAlertEventIds())));
    }

    @DeleteMapping("/recaps/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private String toJson(java.util.List<Long> ids) {
        if (ids == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            throw com.stockcat.common.ApiException.validationError("relatedAlertEventIds 序列化失败");
        }
    }
}
