package com.stockcat.web.dto;

/**
 * 单只基金的手动刷新结果（P1.4）。
 *
 * <p>拆开三类数据分别报告，因为它们的失败原因不同：
 * 行情/IOPV 来自腾讯（Java 直连），净值来自 Python 适配器。前者挂了不影响后者，
 * 用一个 status 概括会让人误判"全挂了"。
 *
 * @param marketUpdated IOPV/价格是否刷新成功（LOF 没有 IOPV，只更新价格）
 * @param navWritten    这次写入了多少期净值
 * @param navSkipped    上游脏行数，非 0 表示数据不完整
 * @param status        SUCCESS 全成功 / PARTIAL 部分成功 / FAILED 无一项成功
 */
public record FundRefreshResponse(
        Long instrumentId,
        String symbol,
        boolean marketUpdated,
        int navWritten,
        int navSkipped,
        String status,
        String code,
        String message,
        long elapsedMs
) {
}
