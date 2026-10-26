package com.stockcat.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 指标查询结果。points 与 K 线逐根对齐（同下标同日期），便于前端直接按 index 画多副图。
 *
 * @param requiredBars 每个指标需要的最少根数，如 {"macd":34,"rsi6":7,"kdj":9}。
 *                     count 小于该值时对应字段整列为 null，前端应提示"数据不足，先同步更多历史"。
 */
public record IndicatorSeriesResponse(
        Long instrumentId,
        String symbol,
        String adjust,
        int count,
        Map<String, Integer> requiredBars,
        List<IndicatorPoint> points
) {
}
