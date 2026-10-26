package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Instrument;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.quote.EastMoneyQuoteClient;
import com.stockcat.quote.StandardQuote;
import com.stockcat.quote.SymbolMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 标的主数据服务。
 * 创建标的时通过行情源验证并自动补全名称（P0.3 起）；数据源不可用且未提供名称时拒绝创建，
 * 绝不生成虚假名称（docs/DEVELOPMENT_PLAN.md P0.3 验收）。
 */
@Service
public class InstrumentService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentService.class);

    private final InstrumentMapper instrumentMapper;
    private final EastMoneyQuoteClient quoteClient;

    public InstrumentService(InstrumentMapper instrumentMapper, EastMoneyQuoteClient quoteClient) {
        this.instrumentMapper = instrumentMapper;
        this.quoteClient = quoteClient;
    }

    /**
     * 查找或创建标的。name 可空：为空时从行情源补全名称；行情源失败时抛 DATA_SOURCE_UNAVAILABLE。
     */
    public Instrument getOrCreateVerified(String market, String symbol, String type, String requestedName) {
        Instrument existing = instrumentMapper.findByMarketAndSymbol(market, symbol);
        if (existing != null) {
            return existing;
        }
        if (!"CN_A".equals(market)) {
            throw ApiException.validationError("P0 仅支持 CN_A 市场，收到: " + market);
        }
        String secid = SymbolMapping.cnAToSecid(symbol);
        if (secid == null) {
            throw ApiException.validationError("无法识别的 A 股代码前缀: " + symbol + "（P0 暂不支持北交所）");
        }

        String resolvedName;
        String sourceCode;
        StandardQuote quote = null;
        try {
            quote = quoteClient.fetch(List.of(secid)).stream()
                    .findFirst()
                    .orElse(null);
        } catch (EastMoneyQuoteClient.QuoteSourceException e) {
            log.warn("创建标的时行情源不可用: {} {}", market, symbol);
        }
        if (quote != null && quote.name() != null && !quote.name().isBlank()) {
            resolvedName = quote.name();
            sourceCode = EastMoneyQuoteClient.SOURCE_CODE;
        } else if (requestedName != null && !requestedName.isBlank()) {
            // 数据源未验证到名称时，允许用户显式提供的名称（非伪造），但标记来源为 manual
            resolvedName = requestedName.trim();
            sourceCode = "manual";
        } else {
            throw new ApiException(com.stockcat.common.ErrorCode.DATA_SOURCE_UNAVAILABLE,
                    "无法从行情源验证标的 " + symbol + "（未获取到名称），且未提供 name。不创建无法验证的标的");
        }

        Instrument instrument = new Instrument();
        instrument.setMarket(market);
        instrument.setSymbol(symbol);
        instrument.setName(resolvedName);
        instrument.setInstrumentType(type);
        instrument.setCurrency("CNY");
        instrument.setSourceCode(sourceCode);
        instrumentMapper.insert(instrument);
        return instrument;
    }
}
