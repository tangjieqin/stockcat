package com.stockcat.web.dto;

import java.util.List;

/**
 * POST /trades/import 响应：逐行结果（SUCCESS/FAILED），失败不影响其他行。
 */
public record ImportTradesResponse(
        int requested,
        int succeeded,
        int failed,
        List<ItemResult> results
) {

    public record ItemResult(int index, String status, String code, String message, Long tradeId) {
        public static ItemResult ok(int index, Long tradeId) {
            return new ItemResult(index, "SUCCESS", null, null, tradeId);
        }

        public static ItemResult fail(int index, String code, String message) {
            return new ItemResult(index, "FAILED", code, message, null);
        }
    }
}
