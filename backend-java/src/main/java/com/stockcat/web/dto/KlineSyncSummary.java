package com.stockcat.web.dto;

/**
 * 单个标的的历史同步结果。
 *
 * <p>status: SUCCESS（有数据落库）/ FAILED（未取到可用数据）/ SKIPPED（代码规则不支持，未尝试）。
 * <p>rejectedUnclosed：上游返回了尚未收盘的当日 bar，被拒写；
 * <p>rejectedInvalid：负价等脏行被拒写；skippedUpstream：上游自己标记的问题行数。
 * 这三项用于暴露「静默丢数据」，不允许只看 accepted 就当成全部成功。
 */
public record KlineSyncSummary(
        Long instrumentId,
        String symbol,
        String adjust,
        String status,
        String code,
        String message,
        int requestedLimit,
        int accepted,
        int rejectedUnclosed,
        int rejectedInvalid,
        int skippedUpstream,
        String source,
        long elapsedMs
) {

    public static KlineSyncSummary failed(Long instrumentId, String symbol, String adjust,
                                          String code, String message, long elapsedMs) {
        return new KlineSyncSummary(instrumentId, symbol, adjust, "FAILED", code, message,
                0, 0, 0, 0, 0, null, elapsedMs);
    }

    public static KlineSyncSummary skipped(Long instrumentId, String symbol, String adjust,
                                           String code, String message, long elapsedMs) {
        return new KlineSyncSummary(instrumentId, symbol, adjust, "SKIPPED", code, message,
                0, 0, 0, 0, 0, null, elapsedMs);
    }
}
