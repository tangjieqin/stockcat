package com.stockcat.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * 自选条目：instrument + 最新可用快照（无快照时 quote 为 null，前端显示 —）。
 * groupName 为 null 表示默认「自选」（V7 自定义分组）。
 */
public record WatchlistItemResponse(
        Long id,
        @JsonProperty("instrument") InstrumentResponse instrument,
        OffsetDateTime createdAt,
        String groupName,
        QuoteResponse quote
) {
}
