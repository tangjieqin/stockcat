package com.stockcat.web;

import com.stockcat.service.KlineQueryService;
import com.stockcat.service.KlineSyncService;
import com.stockcat.web.dto.KlineSeriesResponse;
import com.stockcat.web.dto.KlineSyncBatchSummary;
import com.stockcat.web.dto.KlineSyncSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 历史日 K 查询与同步（P1.2）。
 *
 * <p>同步是显式动作：收盘后由定时任务触发，也可手动补跑。
 * 查询只读库，不会因为上游抖动而变慢或返回半截数据。
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class KlineController {

    private final KlineQueryService queryService;
    private final KlineSyncService syncService;

    public KlineController(KlineQueryService queryService, KlineSyncService syncService) {
        this.queryService = queryService;
        this.syncService = syncService;
    }

    /**
     * 查询已落库的日 K（升序）。count=0 表示尚未同步，不是「该标的无行情」。
     */
    @GetMapping("/instruments/{id}/klines")
    public KlineSeriesResponse klines(
            @PathVariable Long id,
            @RequestParam(defaultValue = KlineSyncService.DEFAULT_ADJUST) String adjust,
            @RequestParam(defaultValue = "120") @Min(1) @Max(KlineSyncService.MAX_LIMIT) int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        String normalized = KlineSyncService.normalizeAdjust(adjust);
        return queryService.query(id, normalized, limit, end);
    }

    /**
     * 手动同步单个标的最近 days 根日 K（幂等 UPSERT，可反复跑）。
     */
    @PostMapping("/instruments/{id}/history/sync")
    public KlineSyncSummary sync(
            @PathVariable Long id,
            @RequestParam(defaultValue = "120") @Min(1) @Max(KlineSyncService.MAX_LIMIT) int days,
            @RequestParam(defaultValue = KlineSyncService.DEFAULT_ADJUST) String adjust) {
        return syncService.syncInstrument(id, days, adjust);
    }

    /**
     * 手动同步全部范围（自选 + 启用监控 + 持仓），用于首次建仓或历史缺口修复。
     */
    @PostMapping("/history/sync")
    public KlineSyncBatchSummary syncAll(
            @RequestParam(defaultValue = "120") @Min(1) @Max(KlineSyncService.MAX_LIMIT) int days) {
        return syncService.syncAll(days);
    }
}
