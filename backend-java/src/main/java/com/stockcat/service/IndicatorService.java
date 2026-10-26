package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Instrument;
import com.stockcat.domain.KlineDaily;
import com.stockcat.indicator.IndicatorCalculator;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.mapper.KlineDailyMapper;
import com.stockcat.web.dto.IndicatorPoint;
import com.stockcat.web.dto.IndicatorSeriesResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 指标查询（P1.3）：读已落库的日 K，现算指标，不落库。
 *
 * <p>不在这里抓上游：指标只依赖本地 K 线；K 线不够就返回 null 序列 + requiredBars，
 * 让前端提示"先同步更多历史"，而不是偷偷去请求数据源（那会让查询变慢且不可复现）。
 */
@Service
public class IndicatorService {

    private final IndicatorCalculator calculator;
    private final KlineDailyMapper klineMapper;
    private final InstrumentMapper instrumentMapper;

    public IndicatorService(IndicatorCalculator calculator,
                            KlineDailyMapper klineMapper,
                            InstrumentMapper instrumentMapper) {
        this.calculator = calculator;
        this.klineMapper = klineMapper;
        this.instrumentMapper = instrumentMapper;
    }

    public IndicatorSeriesResponse query(Long instrumentId, String adjust, int limit) {
        Instrument instrument = instrumentMapper.findById(instrumentId);
        if (instrument == null) {
            throw ApiException.notFound("标的不存在: id=" + instrumentId);
        }
        int size = Math.max(1, Math.min(limit, KlineSyncService.MAX_LIMIT));

        // Mapper 取的是最近 N 根（DESC），指标必须按时间升序计算
        List<KlineDaily> rows = new ArrayList<>(klineMapper.selectRecent(instrumentId, adjust, null, size));
        Collections.reverse(rows);

        List<IndicatorCalculator.IndicatorValues> values = calculator.calculate(rows);
        List<IndicatorPoint> points = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            KlineDaily row = rows.get(i);
            IndicatorCalculator.IndicatorValues v = values.get(i);
            points.add(new IndicatorPoint(
                    row.getTradeDate(), row.getClose(),
                    v.dif(), v.dea(), v.macdHist(),
                    v.rsi6(), v.rsi12(), v.rsi24(),
                    v.k(), v.d(), v.j()));
        }

        return new IndicatorSeriesResponse(
                instrumentId, instrument.getSymbol(), adjust, points.size(),
                calculator.requiredBars(), points);
    }
}
