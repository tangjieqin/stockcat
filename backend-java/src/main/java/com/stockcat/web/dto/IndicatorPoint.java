package com.stockcat.web.dto;

import java.time.LocalDate;

/**
 * 单根 K 线上的指标值。未预热的字段为 null（不是 0）：
 * 0 会被画成贴底的线，看起来像"指标到极值"，比空着更容易误判。
 */
public record IndicatorPoint(
        LocalDate date,
        Double close,
        Double dif,
        Double dea,
        Double macdHist,
        Double rsi6,
        Double rsi12,
        Double rsi24,
        Double k,
        Double d,
        Double j
) {
}
