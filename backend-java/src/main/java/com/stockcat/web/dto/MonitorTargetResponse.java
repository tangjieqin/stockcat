package com.stockcat.web.dto;

import com.stockcat.domain.MonitorTargetRow;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * 监控标的响应。P0.2 行情快照恒为 null（P0.3 接入数据源后填充，缺失时不能为 0）。
 */
public record MonitorTargetResponse(
        Long id,
        @JsonProperty("instrument") InstrumentResponse instrument,
        Boolean enabled,
        OffsetDateTime createdAt,
        Object quote
) {
    public static MonitorTargetResponse from(MonitorTargetRow row) {
        return new MonitorTargetResponse(
                row.getId(),
                new InstrumentResponse(row.getInstrumentId(), row.getMarket(), row.getSymbol(),
                        row.getName(), row.getInstrumentType(), row.getCurrency()),
                row.getEnabled(),
                row.getCreatedAt(),
                null
        );
    }
}
