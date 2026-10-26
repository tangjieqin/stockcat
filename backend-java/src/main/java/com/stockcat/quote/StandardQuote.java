package com.stockcat.quote;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 数据源标准化行情。字段含义见 docs/API.md §2/§3：价格用元，涨跌幅用百分数值。
 */
public record StandardQuote(
        String symbol,          // 如 600519
        String name,            // 数据源返回的名称
        Double lastPrice,       // 最新价
        Double previousClose,   // 昨收
        Double changePercent,   // 涨跌幅百分数值，如 1.25 表示 +1.25%
        Double volume,          // 成交量（手，数据源原始单位）
        Double turnover,        // 成交额（元）
        OffsetDateTime marketTime, // 数据源行情时间（UTC）
        String sourceCode
) {
    public static StandardQuote of(String symbol, String name, Double lastPrice, Double previousClose,
                                   Double changePercent, Double volume, Double turnover,
                                   Long marketTimeEpochSeconds, String sourceCode) {
        OffsetDateTime marketTime = marketTimeEpochSeconds == null
                ? null
                : OffsetDateTime.ofInstant(Instant.ofEpochSecond(marketTimeEpochSeconds), ZoneOffset.UTC);
        return new StandardQuote(symbol, name, lastPrice, previousClose, changePercent,
                volume, turnover, marketTime, sourceCode);
    }
}
