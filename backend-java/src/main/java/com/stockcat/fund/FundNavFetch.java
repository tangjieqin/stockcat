package com.stockcat.fund;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一次净值取数的结果。items 按日期倒序（与上游一致）。
 *
 * @param skippedRows 上游脏行数，非 0 表示数据不完整（必须暴露，不能只看 count）
 */
public record FundNavFetch(
        String fundCode,
        int requestedLimit,
        OffsetDateTime fetchedAt,
        int totalCount,
        int skippedRows,
        List<FundNavPoint> items
) {
}
