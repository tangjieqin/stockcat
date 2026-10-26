package com.stockcat.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单标的在区间内的持仓变化（docs/API.md §6）。
 *
 * <p>期初值取"区间起点之前的最后一条快照"，没有则按 0 起算（区间内才建仓）。
 * {@code realizedPnlInRange} = 期末累计已实现 − 期初累计已实现，是区间内真正落袋的部分。
 */
public record PositionChangeResponse(
        Long instrumentId,
        String symbol,
        String name,
        LocalDate openDate,
        LocalDate closeDate,
        BigDecimal openQuantity,
        BigDecimal closeQuantity,
        BigDecimal quantityDelta,
        BigDecimal openAvgCost,
        BigDecimal closeAvgCost,
        BigDecimal avgCostDelta,
        BigDecimal openMarketValue,
        BigDecimal closeMarketValue,
        BigDecimal marketValueDelta,
        BigDecimal openUnrealized,
        BigDecimal closeUnrealized,
        BigDecimal unrealizedDelta,
        BigDecimal realizedPnlInRange,
        BigDecimal realizedPnlCum,
        long tradeCount,
        boolean closed
) {
}
