package com.stockcat.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * POST /trades/import 单行请求（交割单导入）。
 * 标的按 market+symbol 查找，不存在时自动创建（与自选/监控同一套验证逻辑）。
 */
public record ImportTradeRequest(
        String market,
        String symbol,
        String type,
        String name,
        String direction,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal fee,
        OffsetDateTime tradeTime,
        String note
) {
}
