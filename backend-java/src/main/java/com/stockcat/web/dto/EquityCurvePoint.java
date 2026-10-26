package com.stockcat.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 收益曲线上的一个点（一个交易日收盘）。
 *
 * <p>{@code complete=false} 表示有持仓取不到当日收盘价：市值与浮盈只汇总了能取到价的部分，
 * 曲线在这段会偏低，界面必须提示，不能把它当成"亏了"。
 */
public record EquityCurvePoint(
        LocalDate tradeDate,
        BigDecimal marketValue,
        BigDecimal costValue,
        BigDecimal unrealizedPnl,
        BigDecimal realizedPnlCum,
        BigDecimal totalPnl,
        int positions,
        int pricedPositions,
        boolean complete
) {
}
