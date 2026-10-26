package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Instrument;
import com.stockcat.domain.QuoteSnapshot;
import com.stockcat.mapper.QuoteSnapshotMapper;
import com.stockcat.quote.EastMoneyQuoteClient;
import com.stockcat.quote.StandardQuote;
import com.stockcat.quote.SymbolMapping;
import com.stockcat.web.dto.QuoteResponse;
import com.stockcat.web.dto.RefreshResult;
import com.stockcat.web.dto.RefreshSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 行情快照服务：受控刷新（POST /quotes/refresh）与最新快照查询（GET /quotes/latest）。
 * 数据源失败不写快照、不补 0；过期判定基于 market_time 距采集时间的间隔。
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    private final QuoteSnapshotMapper snapshotMapper;
    private final EastMoneyQuoteClient quoteClient;
    private final Duration staleAfter;

    public QuoteService(QuoteSnapshotMapper snapshotMapper,
                        EastMoneyQuoteClient quoteClient,
                        @Value("${stockcat.quote.stale-after-minutes:30}") long staleAfterMinutes) {
        this.snapshotMapper = snapshotMapper;
        this.quoteClient = quoteClient;
        this.staleAfter = Duration.ofMinutes(staleAfterMinutes);
    }

    /**
     * 批量刷新：按 secid 分组一次拉取，逐条入库；部分失败不影响其他标的。
     */
    public RefreshSummary refresh(List<Long> instrumentIds, List<Instrument> instruments) {
        Map<String, Instrument> bySecid = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        for (Instrument i : instruments) {
            String secid = SymbolMapping.cnAToSecid(i.getSymbol());
            if (secid == null) {
                skipped.add(i.getSymbol());
            } else {
                bySecid.put(secid, i);
            }
        }

        int succeeded = 0;
        int failed = 0;
        List<RefreshResult> results = new ArrayList<>();

        if (!bySecid.isEmpty()) {
            try {
                List<StandardQuote> quotes = quoteClient.fetch(new ArrayList<>(bySecid.keySet()));
                Map<String, StandardQuote> quoteBySymbol = new HashMap<>();
                for (StandardQuote q : quotes) {
                    quoteBySymbol.put(q.symbol(), q);
                }
                for (Map.Entry<String, Instrument> entry : bySecid.entrySet()) {
                    Instrument instrument = entry.getValue();
                    StandardQuote quote = quoteBySymbol.get(instrument.getSymbol());
                    if (quote == null || quote.lastPrice() == null) {
                        failed++;
                        results.add(RefreshResult.failed(instrument.getId(), "NO_DATA"));
                        continue;
                    }
                    persist(instrument.getId(), quote);
                    succeeded++;
                    results.add(RefreshResult.success(instrument.getId()));
                }
            } catch (EastMoneyQuoteClient.QuoteSourceException e) {
                log.warn("批量刷新失败: {}", e.getMessage());
                for (Instrument instrument : bySecid.values()) {
                    failed++;
                    results.add(RefreshResult.failed(instrument.getId(), "DATA_SOURCE_UNAVAILABLE"));
                }
            }
        }
        for (String symbol : skipped) {
            Instrument instrument = instruments.stream().filter(i -> i.getSymbol().equals(symbol)).findFirst().orElseThrow();
            failed++;
            results.add(RefreshResult.failed(instrument.getId(), "UNSUPPORTED_SYMBOL"));
        }
        return new RefreshSummary(instrumentIds.size(), succeeded, failed, results);
    }

    private void persist(Long instrumentId, StandardQuote quote) {
        QuoteSnapshot snapshot = new QuoteSnapshot();
        snapshot.setInstrumentId(instrumentId);
        snapshot.setSourceCode(quote.sourceCode());
        snapshot.setLastPrice(quote.lastPrice());
        snapshot.setPreviousClose(quote.previousClose());
        snapshot.setChangePercent(quote.changePercent());
        snapshot.setVolume(quote.volume());
        snapshot.setTurnover(quote.turnover());
        snapshot.setMarketTime(quote.marketTime());
        snapshot.setDataStatus(statusFor(quote));
        snapshotMapper.insert(snapshot);
    }

    /**
     * 数据状态：行情时间距今超过阈值（默认 30 分钟）标记 STALE，否则 VALID。
     * P0.4 接入交易日历后按交易时段精确判定。
     */
    private String statusFor(StandardQuote quote) {
        if (quote.marketTime() == null) {
            return "PARTIAL";
        }
        OffsetDateTime nowUtc = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        return Duration.between(quote.marketTime(), nowUtc).compareTo(staleAfter) > 0 ? "STALE" : "VALID";
    }

    /**
     * 查询最新快照。无快照的标的不出现在结果中（前端显示 —，不按 0）。
     */
    public Map<Long, QuoteResponse> latest(List<Long> instrumentIds) {
        if (instrumentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, QuoteResponse> map = new HashMap<>();
        for (QuoteSnapshot s : snapshotMapper.selectLatestByInstrumentIds(instrumentIds)) {
            map.put(s.getInstrumentId(), QuoteResponse.from(s));
        }
        return map;
    }
}
