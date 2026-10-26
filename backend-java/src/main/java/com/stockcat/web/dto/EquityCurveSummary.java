package com.stockcat.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 收益曲线区间摘要。
 *
 * <p>口径：总盈亏 = 当前市值 − 净投入资金（Σ买入含费用 − Σ卖出扣费用），
 * 与"浮动盈亏 + 累计已实现"等价。{@code returnPct} 是简单收益率（总盈亏 / 净投入），
 * <b>没有</b>做资金加权（TWR），也不调整分红送股。
 */
public record EquityCurveSummary(
        LocalDate firstDate,
        LocalDate lastDate,
        BigDecimal startMarketValue,
        BigDecimal endMarketValue,
        BigDecimal marketValueChange,
        BigDecimal endCostValue,
        BigDecimal endUnrealizedPnl,
        BigDecimal endRealizedPnlCum,
        BigDecimal endTotalPnl,
        BigDecimal netInvested,
        BigDecimal returnPct
) {
}
