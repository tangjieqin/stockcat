package com.stockcat.web.dto;

/**
 * 单个标的的刷新结果，对应 docs/API.md §3 POST /quotes/refresh。
 */
public record RefreshResult(Long instrumentId, String status, String code) {

    public static RefreshResult success(Long instrumentId) {
        return new RefreshResult(instrumentId, "SUCCESS", null);
    }

    public static RefreshResult failed(Long instrumentId, String code) {
        return new RefreshResult(instrumentId, "FAILED", code);
    }
}
