package com.stockcat.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 收益曲线（docs/API.md §6）。
 *
 * @param lastSnapshotDate 库里最新快照日；比最后一个点更早说明快照没跟上（比如今天还没跑收盘任务）
 * @param priceAdjust      收盘价口径，固定 raw（不复权）——不调整分红送股，见 note
 * @param note             口径与限制说明，前端原样展示，避免把"简单收益"当成净值增长率
 */
public record EquityCurveResponse(
        LocalDate from,
        LocalDate to,
        List<EquityCurvePoint> points,
        EquityCurveSummary summary,
        LocalDate lastSnapshotDate,
        String priceAdjust,
        String note
) {
}
