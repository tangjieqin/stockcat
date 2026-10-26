package com.stockcat.web;

import com.stockcat.domain.Instrument;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.service.FundReferenceService;
import com.stockcat.web.dto.FundPremiumResponse;
import com.stockcat.web.dto.FundReferenceResponse;
import com.stockcat.web.dto.FundRefreshResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 基金参考数据与溢价（P1.4）。
 *
 * <p>只有 ETF/LOF 适用；股票查询返回 {@code applicable=false} 而不是 404 ——
 * "没有这个概念"和"数据查不到"是两件事，前端要显示不同文案。
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class FundReferenceController {

    private final FundReferenceService fundService;
    private final InstrumentMapper instrumentMapper;

    public FundReferenceController(FundReferenceService fundService, InstrumentMapper instrumentMapper) {
        this.fundService = fundService;
        this.instrumentMapper = instrumentMapper;
    }

    /**
     * 单只基金的当前溢价 + 最近 days 个交易日。
     */
    @GetMapping("/instruments/{id}/fund-reference")
    public FundReferenceResponse fundReference(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") @Min(1) @Max(FundReferenceService.MAX_DAYS) int days) {
        return fundService.query(id, days);
    }

    /**
     * 批量取当前溢价（自选列表用）。股票不出现在返回的 Map 里。
     */
    @GetMapping("/fund-references/latest")
    public Map<Long, FundPremiumResponse> latest(@RequestParam("instrumentIds") List<Long> instrumentIds) {
        if (instrumentIds.isEmpty()) {
            return Map.of();
        }
        List<Instrument> instruments = instrumentMapper.selectByIds(instrumentIds);
        return fundService.latestPremiums(instruments);
    }

    /**
     * 手动刷新单只基金：腾讯 IOPV/价格 + Python 净值两条都跑，结果分开返回。
     */
    @PostMapping("/instruments/{id}/fund-reference/refresh")
    public FundRefreshResponse refresh(@PathVariable Long id) {
        return fundService.refresh(id);
    }
}
