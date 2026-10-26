package com.stockcat.quote;

import java.time.OffsetDateTime;

/**
 * 腾讯行情里的基金字段（P1.4）。字段口径见 docs/DATA_SOURCES.md §3.1。
 *
 * @param code       6 位代码（如 510300）
 * @param name       上游名称
 * @param type       上游类型：ETF / LOF / GP-A
 * @param price      最新价
 * @param iopv       参考净值，**仅 ETF 有**；LOF/股票为 null
 * @param nav        最新公布的单位净值（不带日期，故本类不用它算溢价）
 * @param marketTime 行情时间（Asia/Shanghai）
 */
public record FundQuote(
        String code,
        String name,
        String type,
        Double price,
        Double iopv,
        Double nav,
        OffsetDateTime marketTime
) {
}
