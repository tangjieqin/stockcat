package com.stockcat.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * K 线查询结果（升序）。bars 为空表示「尚未同步」，不是「该标的没有行情」——
 * 前端应提示先同步，不要渲染成一条水平的假线。
 */
public record KlineSeriesResponse(
        Long instrumentId,
        String symbol,
        String adjust,
        int count,
        String sourceCode,
        OffsetDateTime lastFetchedAt,
        List<KlineBarResponse> bars
) {
}
