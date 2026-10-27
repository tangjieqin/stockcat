package com.stockcat.web.dto;

import jakarta.validation.constraints.Size;

/**
 * PATCH /watchlist/{instrumentId}/group 请求体。
 * groupName 传 null 或空白 = 移回默认「自选」。
 */
public record MoveWatchlistGroupRequest(
        @Size(max = 64, message = "分组名最长 64 字符") String groupName
) {
}
