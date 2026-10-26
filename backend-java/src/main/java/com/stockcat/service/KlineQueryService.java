package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Instrument;
import com.stockcat.domain.KlineDaily;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.mapper.KlineDailyMapper;
import com.stockcat.web.dto.KlineBarResponse;
import com.stockcat.web.dto.KlineSeriesResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * K 线查询（P1.2）。只读已落库数据，不在查询时抓上游——
 * 让「查询」与「同步」的失败面分开：查询慢/空，原因永远是「还没同步」，不是「上游抖动」。
 */
@Service
public class KlineQueryService {

    private final KlineDailyMapper klineMapper;
    private final InstrumentMapper instrumentMapper;

    public KlineQueryService(KlineDailyMapper klineMapper, InstrumentMapper instrumentMapper) {
        this.klineMapper = klineMapper;
        this.instrumentMapper = instrumentMapper;
    }

    public KlineSeriesResponse query(Long instrumentId, String adjust, int limit, LocalDate end) {
        Instrument instrument = instrumentMapper.findById(instrumentId);
        if (instrument == null) {
            throw ApiException.notFound("标的不存在: id=" + instrumentId);
        }
        int size = Math.max(1, Math.min(limit, KlineSyncService.MAX_LIMIT));
        List<KlineDaily> rows = new ArrayList<>(klineMapper.selectRecent(instrumentId, adjust, end, size));
        // Mapper 按 trade_date DESC 取最近 N 根，返回前端前反转成升序（图表要求）
        Collections.reverse(rows);

        List<KlineBarResponse> bars = rows.stream()
                .map(r -> new KlineBarResponse(r.getTradeDate(), r.getOpen(), r.getHigh(), r.getLow(), r.getClose(),
                        r.getVolume(), r.getAmount(), r.getChangePercent(), r.getTurnoverRate()))
                .toList();

        KlineDaily newest = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        return new KlineSeriesResponse(
                instrumentId,
                instrument.getSymbol(),
                adjust,
                bars.size(),
                newest == null ? null : newest.getSourceCode(),
                newest == null ? null : newest.getFetchedAt(),
                bars);
    }
}
