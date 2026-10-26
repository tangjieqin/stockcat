package com.stockcat.web.dto;

import com.stockcat.domain.Instrument;

public record InstrumentResponse(
        Long id,
        String market,
        String symbol,
        String name,
        String type,
        String currency
) {
    public static InstrumentResponse from(Instrument i) {
        return new InstrumentResponse(i.getId(), i.getMarket(), i.getSymbol(), i.getName(),
                i.getInstrumentType(), i.getCurrency());
    }
}
