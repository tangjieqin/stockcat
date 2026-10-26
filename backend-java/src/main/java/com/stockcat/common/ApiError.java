package com.stockcat.common;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 统一错误响应体，对应 docs/API.md §1：
 * { timestamp, status, code, message, details, path }
 */
public record ApiError(
        OffsetDateTime timestamp,
        int status,
        String code,
        String message,
        List<String> details,
        String path
) {
    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(OffsetDateTime.now(), status, code, message, List.of(), path);
    }

    public static ApiError of(int status, String code, String message, List<String> details, String path) {
        return new ApiError(OffsetDateTime.now(), status, code, message, details, path);
    }
}
