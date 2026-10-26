package com.stockcat.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 持仓响应（docs/API.md §6 GET /positions）。
 * 快照缺失/过期时 lastPrice/marketValue/unrealizedPnl 为 null 并标注 priceStatus，不按 0 计算。
 */
public record PositionResponse(
        Long instrumentId,
        String symbol,
        String name,
        BigDecimal quantity,
        BigDecimal avgCost,
        BigDecimal lastPrice,
        BigDecimal marketValue,
        BigDecimal unrealizedPnl,
        BigDecimal realizedPnl,
        OffsetDateTime priceAsOf,
        String priceStatus
) {
}
