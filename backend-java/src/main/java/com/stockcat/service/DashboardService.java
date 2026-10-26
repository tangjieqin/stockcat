package com.stockcat.service;

import com.stockcat.domain.MarketRecap;
import com.stockcat.mapper.AlertEventMapper;
import com.stockcat.mapper.MarketRecapMapper;
import com.stockcat.mapper.WatchlistMapper;
import com.stockcat.web.dto.QuoteResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 首页总览聚合（docs/API.md §8）。P0 简单串行聚合，不做专门优化。
 * 任一块数据缺失返回空集合/null，不影响其他块；不伪造数据。
 */
@Service
public class DashboardService {

    private final IndexService indexService;
    private final PositionService positionService;
    private final QuoteService quoteService;
    private final WatchlistMapper watchlistMapper;
    private final AlertEventMapper alertEventMapper;
    private final MarketRecapMapper recapMapper;

    public DashboardService(IndexService indexService, PositionService positionService,
                            QuoteService quoteService, WatchlistMapper watchlistMapper,
                            AlertEventMapper alertEventMapper, MarketRecapMapper recapMapper) {
        this.indexService = indexService;
        this.positionService = positionService;
        this.quoteService = quoteService;
        this.watchlistMapper = watchlistMapper;
        this.alertEventMapper = alertEventMapper;
        this.recapMapper = recapMapper;
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> body = new LinkedHashMap<>();

        // 1. 指数行情（内置配置，调度器刷新）
        body.put("indices", indexService.listLatest());

        // 2. 持仓摘要
        var positions = positionService.list();
        BigDecimal totalMarketValue = BigDecimal.ZERO;
        BigDecimal totalUnrealized = BigDecimal.ZERO;
        for (var p : positions) {
            if (p.marketValue() != null) totalMarketValue = totalMarketValue.add(p.marketValue());
            if (p.unrealizedPnl() != null) totalUnrealized = totalUnrealized.add(p.unrealizedPnl());
        }
        body.put("positionsSummary", Map.of(
                "totalMarketValue", totalMarketValue,
                "totalUnrealizedPnl", totalUnrealized,
                "positionCount", positions.size()));

        // 3. 自选标的涨跌幅居前（按绝对值）
        List<Long> watchIds = watchlistMapper.selectAll().stream()
                .map(com.stockcat.domain.MonitorTargetRow::getInstrumentId).toList();
        Map<Long, QuoteResponse> quotes = quoteService.latest(watchIds);
        List<Map<String, Object>> topMovers = quotes.entrySet().stream()
                .filter(e -> e.getValue().changePercent() != null)
                .sorted((a, b) -> Double.compare(
                        Math.abs(b.getValue().changePercent()), Math.abs(a.getValue().changePercent())))
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("instrumentId", e.getKey());
                    m.put("changePercent", e.getValue().changePercent());
                    m.put("lastPrice", e.getValue().lastPrice());
                    return m;
                })
                .collect(Collectors.toList());
        body.put("topMovers", topMovers);

        // 4. 最近告警事件（P0 无已读状态，取最近 5 条）
        var events = alertEventMapper.selectPage(null, null, null, null, 0, 5);
        body.put("pendingAlerts", events.stream()
                .map(e -> Map.<String, Object>of(
                        "eventId", e.getId(),
                        "symbol", e.getSymbol() == null ? "" : e.getSymbol(),
                        "ruleName", e.getRuleNameSnapshot(),
                        "triggeredAt", e.getTriggeredAt()))
                .toList());

        // 5. 最新复盘入口
        List<MarketRecap> recaps = recapMapper.selectPage(null, null, null, null, 0, 1);
        if (!recaps.isEmpty()) {
            MarketRecap latest = recaps.get(0);
            body.put("latestRecap", Map.of(
                    "recapId", latest.getId(),
                    "recapDate", latest.getRecapDate(),
                    "title", latest.getTitle(),
                    "status", latest.getStatus()));
        } else {
            body.put("latestRecap", null);
        }
        return body;
    }
}
