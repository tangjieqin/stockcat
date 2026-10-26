package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Instrument;
import com.stockcat.mapper.WatchlistMapper;
import com.stockcat.web.dto.CreateWatchlistRequest;
import com.stockcat.web.dto.QuoteResponse;
import com.stockcat.web.dto.WatchlistItemResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 自选列表（与监控标的相互独立）。对应 docs/API.md §2。
 */
@Service
public class WatchlistService {

    private final WatchlistMapper watchlistMapper;
    private final InstrumentService instrumentService;
    private final QuoteService quoteService;

    public WatchlistService(WatchlistMapper watchlistMapper,
                            InstrumentService instrumentService,
                            QuoteService quoteService) {
        this.watchlistMapper = watchlistMapper;
        this.instrumentService = instrumentService;
        this.quoteService = quoteService;
    }

    public List<WatchlistItemResponse> list() {
        List<WatchlistItemResponse> items = watchlistMapper.selectAll().stream()
                .map(row -> new WatchlistItemResponse(
                        row.getId(),
                        new com.stockcat.web.dto.InstrumentResponse(row.getInstrumentId(), row.getMarket(),
                                row.getSymbol(), row.getName(), row.getInstrumentType(), row.getCurrency()),
                        row.getCreatedAt(),
                        null))
                .toList();
        if (items.isEmpty()) {
            return items;
        }
        Map<Long, QuoteResponse> quotes = quoteService.latest(items.stream().map(WatchlistItemResponse::id).toList());
        return items.stream()
                .map(item -> new WatchlistItemResponse(item.id(), item.instrument(), item.createdAt(), quotes.get(item.id())))
                .toList();
    }

    @Transactional
    public WatchlistItemResponse add(CreateWatchlistRequest req) {
        Instrument instrument = instrumentService.getOrCreateVerified(req.market(), req.symbol(), req.type(), req.name());
        if (watchlistMapper.existsByInstrumentId(instrument.getId()) > 0) {
            throw ApiException.conflict("该标的已在自选列表中: " + instrument.getSymbol() + " " + instrument.getName());
        }
        watchlistMapper.insert(instrument.getId());
        Long id = watchlistMapper.findIdByInstrumentId(instrument.getId());
        return new WatchlistItemResponse(id,
                com.stockcat.web.dto.InstrumentResponse.from(instrument),
                null,
                null);
    }

    public void delete(Long instrumentId) {
        int deleted = watchlistMapper.deleteByInstrumentId(instrumentId);
        if (deleted == 0) {
            throw ApiException.notFound("自选条目不存在: instrumentId=" + instrumentId);
        }
    }
}
