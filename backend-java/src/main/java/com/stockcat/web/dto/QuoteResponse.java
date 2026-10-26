package com.stockcat.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stockcat.domain.QuoteSnapshot;

import java.time.OffsetDateTime;

/**
 * 最新行情响应，对应 docs/API.md §2 GET /watchlist 的 quote 结构。
 * 数值缺失时为 null（不能为 0）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record QuoteResponse(
        Double lastPrice,
        Double previousClose,
        Double changePercent,
        Double volume,
        Double turnover,
        OffsetDateTime marketTime,
        OffsetDateTime collectedAt,
        String dataStatus,
        String source
) {
    public static QuoteResponse from(QuoteSnapshot s) {
        return new QuoteResponse(s.getLastPrice(), s.getPreviousClose(), s.getChangePercent(),
                s.getVolume(), s.getTurnover(), s.getMarketTime(), s.getCollectedAt(),
                s.getDataStatus(), s.getSourceCode());
    }
}
