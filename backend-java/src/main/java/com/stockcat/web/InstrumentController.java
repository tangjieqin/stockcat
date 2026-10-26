package com.stockcat.web;

import com.stockcat.domain.Instrument;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.web.dto.InstrumentResponse;
import com.stockcat.web.dto.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标的主数据查询，对应 docs/API.md §2 GET /instruments。
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class InstrumentController {

    private final InstrumentMapper instrumentMapper;

    public InstrumentController(InstrumentMapper instrumentMapper) {
        this.instrumentMapper = instrumentMapper;
    }

    @GetMapping("/instruments")
    public PageResponse<InstrumentResponse> list(
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        List<Instrument> rows = instrumentMapper.selectPage(market, type, keyword, page * size, size);
        long total = instrumentMapper.countPage(market, type, keyword);
        return new PageResponse<>(rows.stream().map(InstrumentResponse::from).toList(), page, size, total);
    }
}
