package com.stockcat.web.dto;

import java.time.LocalDate;

/**
 * 单根日 K。价格字段为复权后口径（默认 qfq），volume 单位为手，amount 单位为元。
 */
public record KlineBarResponse(
        LocalDate date,
        Double open,
        Double high,
        Double low,
        Double close,
        Double volume,
        Double amount,
        Double changePercent,
        Double turnoverRate
) {
}
