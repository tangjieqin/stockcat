package com.stockcat.web;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Instrument;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.web.dto.InstrumentResponse;
import com.stockcat.web.dto.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * 单个标的主数据（P1.7 详情页直接按 id 打开，不能靠列表搜索猜出来的那条）。
     * 不存在时返回 404，前端显示"标的不存在"而不是空白页。
     */
    @GetMapping("/instruments/{id}")
    public InstrumentResponse get(@PathVariable Long id) {
        Instrument row = instrumentMapper.findById(id);
        if (row == null) {
            throw ApiException.notFound("标的不存在: id=" + id);
        }
        return InstrumentResponse.from(row);
    }
}
