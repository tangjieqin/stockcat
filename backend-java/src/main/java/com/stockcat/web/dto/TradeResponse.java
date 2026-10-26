package com.stockcat.web.dto;

import com.stockcat.domain.Trade;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TradeResponse(
        Long id,
        Long instrumentId,
        String symbol,
        String instrumentName,
        String direction,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal fee,
        OffsetDateTime tradeTime,
        String note
) {
    public static TradeResponse from(Trade t) {
        return new TradeResponse(t.getId(), t.getInstrumentId(), t.getSymbol(), t.getInstrumentName(),
                t.getDirection(), t.getPrice(), t.getQuantity(), t.getFee(), t.getTradeTime(), t.getNote());
    }
}
