package com.stockcat.history;

import java.time.LocalDate;

/**
 * 上游返回的原始日 K。字段可为 null（停牌、上游缺列），是否可用由 Java 侧判定。
 * 单位：volume 为手，amount 为元；changePercent / turnoverRate 为百分数值。
 */
public record DailyBar(
        LocalDate tradeDate,
        Double open,
        Double high,
        Double low,
        Double close,
        Double volume,
        Double amount,
        Double changePercent,
        Double turnoverRate
) {
}
