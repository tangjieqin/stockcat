package com.stockcat.web.dto;

import java.util.List;

/**
 * 批量同步统计：成功/失败/跳过标的数 + 总耗时，沿用 P0 调度可观测性的写法。
 */
public record KlineSyncBatchSummary(
        int instruments,
        int succeeded,
        int failed,
        int skipped,
        long elapsedMs,
        List<KlineSyncSummary> results
) {
}
