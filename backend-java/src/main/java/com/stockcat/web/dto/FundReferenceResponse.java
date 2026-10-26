package com.stockcat.web.dto;

import java.util.List;

/**
 * 基金参考数据查询结果（P1.4）。
 *
 * @param applicable 是否适用（只有 ETF/LOF 有净值与溢价）；false 时 {@code reason} 说明原因
 * @param reason     不适用或暂无数据的原因，前端直接展示，不自己编文案
 * @param latest     当前溢价视图；无任何记录时为 null
 * @param history    最近 N 个交易日（倒序），每行按**当天**的价格与基准计算溢价
 */
public record FundReferenceResponse(
        Long instrumentId,
        String symbol,
        String type,
        boolean applicable,
        String reason,
        FundPremiumResponse latest,
        List<FundPremiumResponse> history
) {
}
