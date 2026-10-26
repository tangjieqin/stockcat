package com.stockcat.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * POST /monitor-targets 请求体，对应 docs/API.md §2。
 * name：instrument 不存在时必填（P0 无数据源，不能伪造名称）。
 */
public record CreateMonitorTargetRequest(
        @NotBlank @Pattern(regexp = "^[A-Z0-9_]{1,16}$", message = "市场标识不合法，如 CN_A") String market,
        @NotBlank @Pattern(regexp = "^[0-9A-Za-z.]{1,32}$", message = "标的代码不合法") String symbol,
        @NotBlank @Pattern(regexp = "^(STOCK|ETF|LOF)$", message = "type 仅支持 STOCK/ETF/LOF") String type,
        String name
) {
}
