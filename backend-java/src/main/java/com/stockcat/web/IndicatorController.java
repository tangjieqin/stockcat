package com.stockcat.web;

import com.stockcat.service.IndicatorService;
import com.stockcat.service.KlineSyncService;
import com.stockcat.web.dto.IndicatorSeriesResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 技术指标查询（P1.3）。指标现算不落库，参数固定为 MACD(12,26,9) / RSI(6,12,24) / KDJ(9,3,3)。
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class IndicatorController {

    private final IndicatorService indicatorService;

    public IndicatorController(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    /**
     * @param limit 参与计算的 K 线根数。MACD 至少需要 34 根，建议 120~250。
     */
    @GetMapping("/instruments/{id}/indicators")
    public IndicatorSeriesResponse indicators(
            @PathVariable Long id,
            @RequestParam(defaultValue = KlineSyncService.DEFAULT_ADJUST) String adjust,
            @RequestParam(defaultValue = "250") @Min(1) @Max(KlineSyncService.MAX_LIMIT) int limit) {
        String normalized = KlineSyncService.normalizeAdjust(adjust);
        return indicatorService.query(id, normalized, limit);
    }
}
