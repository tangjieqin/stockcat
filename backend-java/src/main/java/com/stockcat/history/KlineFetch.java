package com.stockcat.history;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一次历史行情抓取的结果。bars 为上游原样数据（已按日期升序），未经业务过滤。
 */
public record KlineFetch(
        String source,
        String secid,
        String symbol,
        String adjust,
        int requestedLimit,
        OffsetDateTime fetchedAt,
        int barCount,
        int skippedBars,
        List<DailyBar> bars
) {
}
