package com.stockcat.web;

import com.stockcat.service.PortfolioService;
import com.stockcat.service.PositionSnapshotService;
import com.stockcat.web.dto.EquityCurveResponse;
import com.stockcat.web.dto.PositionChangeResponse;
import com.stockcat.web.dto.PositionRebuildResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 组合层接口（P1.5，docs/API.md §6）：收益曲线、持仓变化、快照重建。
 */
@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_REBUILD_DAYS = 30;

    private final PortfolioService portfolioService;
    private final PositionSnapshotService snapshotService;

    public PortfolioController(PortfolioService portfolioService, PositionSnapshotService snapshotService) {
        this.portfolioService = portfolioService;
        this.snapshotService = snapshotService;
    }

    /** 区间收益曲线；from/to 都省略时取最近 180 天（右端为库里最新快照日）。 */
    @GetMapping("/equity-curve")
    public EquityCurveResponse equityCurve(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return portfolioService.equityCurve(from, to);
    }

    /** 区间内每个标的的持仓/成本/盈亏变化。 */
    @GetMapping("/position-changes")
    public List<PositionChangeResponse> positionChanges(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return portfolioService.positionChanges(from, to);
    }

    /**
     * 手动重建快照（改过流水后用；收盘任务每天也会跑一次）。
     * 默认重建最近 30 天，to 默认今天。
     */
    @PostMapping("/snapshots/rebuild")
    public PositionRebuildResponse rebuild(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(CN);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_REBUILD_DAYS);
        PositionSnapshotService.RebuildResult result = snapshotService.rebuild(start, end, true);
        return new PositionRebuildResponse(result.from(), result.to(), result.tradeDates(),
                result.instruments(), result.rows(), result.syncedInstruments(),
                result.warnings(), result.elapsedMs());
    }
}
