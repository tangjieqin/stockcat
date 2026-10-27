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
 * V7 起支持自定义分组：group_name 为 NULL 表示默认「自选」；一个标的只属于一个分组。
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
                        row.getGroupName(),
                        null))
                .toList();
        if (items.isEmpty()) {
            return items;
        }
        Map<Long, QuoteResponse> quotes = quoteService.latest(items.stream().map(WatchlistItemResponse::id).toList());
        return items.stream()
                .map(item -> new WatchlistItemResponse(item.id(), item.instrument(), item.createdAt(),
                        item.groupName(), quotes.get(item.id())))
                .toList();
    }

    /** 已有分组名（不含默认「自选」）。 */
    public List<String> groups() {
        return watchlistMapper.selectDistinctGroups();
    }

    @Transactional
    public WatchlistItemResponse add(CreateWatchlistRequest req) {
        Instrument instrument = instrumentService.getOrCreateVerified(req.market(), req.symbol(), req.type(), req.name());
        if (watchlistMapper.existsByInstrumentId(instrument.getId()) > 0) {
            throw ApiException.conflict("该标的已在自选列表中: " + instrument.getSymbol() + " " + instrument.getName());
        }
        String groupName = normalizeGroup(req.groupName());
        watchlistMapper.insert(instrument.getId(), groupName);
        Long id = watchlistMapper.findIdByInstrumentId(instrument.getId());
        return new WatchlistItemResponse(id,
                com.stockcat.web.dto.InstrumentResponse.from(instrument),
                null,
                groupName,
                null);
    }

    /** 移动到分组；groupName 为 null/空白 = 移回默认「自选」。 */
    @Transactional
    public WatchlistItemResponse moveToGroup(Long instrumentId, String groupName) {
        String normalized = normalizeGroup(groupName);
        int updated = watchlistMapper.updateGroupName(instrumentId, normalized);
        if (updated == 0) {
            throw ApiException.notFound("自选条目不存在: instrumentId=" + instrumentId);
        }
        return findById(instrumentId);
    }

    public void delete(Long instrumentId) {
        int deleted = watchlistMapper.deleteByInstrumentId(instrumentId);
        if (deleted == 0) {
            throw ApiException.notFound("自选条目不存在: instrumentId=" + instrumentId);
        }
    }

    private WatchlistItemResponse findById(Long instrumentId) {
        return list().stream()
                .filter(item -> item.instrument().id().equals(instrumentId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("自选条目不存在: instrumentId=" + instrumentId));
    }

    /** 空白 → null（默认自选）；去掉首尾空白。 */
    private String normalizeGroup(String groupName) {
        if (groupName == null) {
            return null;
        }
        String trimmed = groupName.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
