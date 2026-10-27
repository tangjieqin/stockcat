package com.stockcat.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /watchlist 请求体。name 可选：P0.3 起服务端可从行情源自动补全名称。
 * groupName 可选：V7 自定义分组；null/空白 = 默认「自选」。
 */
public record CreateWatchlistRequest(
        @NotBlank @Pattern(regexp = "^[A-Z0-9_]{1,16}$", message = "市场标识不合法，如 CN_A") String market,
        @NotBlank @Pattern(regexp = "^[0-9A-Za-z.]{1,32}$", message = "标的代码不合法") String symbol,
        @NotBlank @Pattern(regexp = "^(STOCK|ETF|LOF)$", message = "type 仅支持 STOCK/ETF/LOF") String type,
        String name,
        @Size(max = 64, message = "分组名最长 64 字符") String groupName
) {
}
