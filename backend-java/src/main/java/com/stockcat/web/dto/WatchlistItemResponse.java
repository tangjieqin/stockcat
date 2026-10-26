package com.stockcat.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * 自选条目：instrument + 最新可用快照（无快照时 quote 为 null，前端显示 —）。
 */
public record WatchlistItemResponse(
        Long id,
        @JsonProperty("instrument") InstrumentResponse instrument,
        OffsetDateTime createdAt,
        QuoteResponse quote
) {
}
