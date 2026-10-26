package com.stockcat.web.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 基金溢价视图（P1.4）。同一结构既用于「当前」也用于「历史某一天」。
 *
 * <p>溢价率的三个伴随字段缺一不可（docs/DATA_SOURCES.md §3.1）：
 * <ul>
 *   <li>{@code premiumBasis}：IOPV（实时参考净值）还是 NAV（单位净值）</li>
 *   <li>{@code premiumAsOf}：基准值的时点（IOPV 是时间戳，NAV 是日期）</li>
 *   <li>{@code navStaleDays}：净值距行情日的自然日差；LOF 盘中必然是 1 天以上</li>
 * </ul>
 * 三者都没有时 {@code premiumRate} 为 null——不猜、不拿别的数顶替。
 *
 * @param tradeDate 行情日期
 * @param price     采集当时的价格
 * @param iopv      参考净值（仅 ETF）
 * @param nav       单位净值
 * @param navDate   净值所属日期
 */
public record FundPremiumResponse(
        LocalDate tradeDate,
        Double price,
        Double iopv,
        OffsetDateTime iopvTime,
        Double nav,
        LocalDate navDate,
        Long navStaleDays,
        Double premiumRate,
        String premiumBasis,
        String premiumAsOf,
        String priceSource,
        String navSource,
        OffsetDateTime fetchedAt
) {
}
