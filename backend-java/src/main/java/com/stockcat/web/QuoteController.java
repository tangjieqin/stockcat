package com.stockcat.web;

import com.stockcat.domain.Instrument;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.service.QuoteService;
import com.stockcat.web.dto.RefreshSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 行情查询与受控刷新，对应 docs/API.md §3。
 */
@RestController
@RequestMapping("/api/v1")
public class QuoteController {

    private final QuoteService quoteService;
    private final InstrumentMapper instrumentMapper;

    public QuoteController(QuoteService quoteService, InstrumentMapper instrumentMapper) {
        this.quoteService = quoteService;
        this.instrumentMapper = instrumentMapper;
    }

    @GetMapping("/quotes/latest")
    public Map<Long, com.stockcat.web.dto.QuoteResponse> latest(
            @RequestParam("instrumentIds") List<Long> instrumentIds,
            @RequestParam(defaultValue = "false") boolean includeStale) {
        // includeStale：P0.3 快照仅保留最新一条，STALE 与否由 dataStatus 字段表达
        return quoteService.latest(instrumentIds);
    }

    public record RefreshRequest(@NotEmpty List<Long> instrumentIds) {
    }

    @PostMapping("/quotes/refresh")
    public RefreshSummary refresh(@Valid @RequestBody RefreshRequest req) {
        List<Instrument> instruments = req.instrumentIds().stream()
                .map(instrumentMapper::findById)
                .filter(Objects::nonNull)
                .toList();
        return quoteService.refresh(req.instrumentIds(), instruments);
    }
}
