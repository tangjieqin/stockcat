package com.stockcat.collector;

import com.stockcat.domain.Instrument;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.mapper.MonitorTargetMapper;
import com.stockcat.mapper.WatchlistMapper;
import com.stockcat.service.IndexService;
import com.stockcat.service.QuoteService;
import com.stockcat.service.TradingCalendarService;
import com.stockcat.web.dto.RefreshSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 行情采集调度器（P0.4）。
 * - 仅在交易日 + 采集窗口内执行（TradingCalendarService 判定）
 * - AtomicBoolean 防止单进程内重入（docs/DEVELOPMENT_PLAN.md P0.4 验收）
 * - 采集范围：启用的监控标的 + 自选标的（去重）+ 内置指数
 */
@Component
public class QuoteCollectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuoteCollectorScheduler.class);

    private final TradingCalendarService calendarService;
    private final QuoteService quoteService;
    private final IndexService indexService;
    private final MonitorTargetMapper monitorTargetMapper;
    private final WatchlistMapper watchlistMapper;
    private final InstrumentMapper instrumentMapper;
    private final CollectorStats stats;
    private final boolean collectEnabled;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public QuoteCollectorScheduler(TradingCalendarService calendarService,
                                   QuoteService quoteService,
                                   IndexService indexService,
                                   MonitorTargetMapper monitorTargetMapper,
                                   WatchlistMapper watchlistMapper,
                                   InstrumentMapper instrumentMapper,
                                   CollectorStats stats,
                                   @Value("${stockcat.collect.enabled:true}") boolean collectEnabled) {
        this.calendarService = calendarService;
        this.quoteService = quoteService;
        this.indexService = indexService;
        this.monitorTargetMapper = monitorTargetMapper;
        this.watchlistMapper = watchlistMapper;
        this.instrumentMapper = instrumentMapper;
        this.stats = stats;
        this.collectEnabled = collectEnabled;
    }

    /**
     * fixedDelay 保证上一轮结束后再计时，配合 running 标志双保险防重入。
     */
    @Scheduled(fixedDelayString = "${stockcat.collect.interval-seconds:15}000", initialDelayString = "5000")
    public void collect() {
        if (!collectEnabled) {
            return;
        }
        if (!calendarService.shouldCollectNow()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("上一轮采集尚未结束，本轮跳过（防重入）");
            return;
        }
        try {
            stats.recordAttempt();
            doCollect();
        } catch (Exception e) {
            log.error("采集任务异常: {}", e.getMessage(), e);
            stats.recordFailure("COLLECTOR_ERROR");
        } finally {
            running.set(false);
        }
    }

    private void doCollect() {
        // 1. 个股/基金：启用中的监控标的 + 自选（并集去重）
        LinkedHashSet<Long> instrumentIds = new LinkedHashSet<>();
        monitorTargetMapper.selectAll().forEach(row -> {
            if (Boolean.TRUE.equals(row.getEnabled())) {
                instrumentIds.add(row.getInstrumentId());
            }
        });
        watchlistMapper.selectAll().forEach(row -> instrumentIds.add(row.getInstrumentId()));

        int failedQuotes = 0;
        if (!instrumentIds.isEmpty()) {
            List<Instrument> instruments = instrumentIds.stream()
                    .map(instrumentMapper::findById)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            RefreshSummary summary = quoteService.refresh(new ArrayList<>(instrumentIds), instruments);
            failedQuotes = summary.failed();
            log.info("采集完成: 成功 {} 失败 {} / 共 {}", summary.succeeded(), summary.failed(), summary.requested());
        }

        // 2. 内置指数（失败不影响个股）
        indexService.refresh();

        stats.recordSuccess(instrumentIds.size(), failedQuotes);
    }
}
