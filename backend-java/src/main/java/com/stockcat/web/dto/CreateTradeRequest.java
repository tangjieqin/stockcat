package com.stockcat.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** POST /trades 请求体（docs/API.md §6） */
public record CreateTradeRequest(
        @NotNull Long instrumentId,
        @NotBlank String direction,
        @NotNull BigDecimal price,
        @NotNull BigDecimal quantity,
        BigDecimal fee,
        @NotNull OffsetDateTime tradeTime,
        String note
) {
}
