package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Instrument;
import com.stockcat.domain.KlineDaily;
import com.stockcat.history.DailyBar;
import com.stockcat.history.HistoryDataClient;
import com.stockcat.history.HistorySourceException;
import com.stockcat.history.KlineFetch;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.mapper.KlineDailyMapper;
import com.stockcat.mapper.MonitorTargetMapper;
import com.stockcat.mapper.WatchlistMapper;
import com.stockcat.quote.SymbolMapping;
import com.stockcat.system.JobRunStats;
import com.stockcat.web.dto.KlineSyncBatchSummary;
import com.stockcat.web.dto.KlineSyncSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 历史日 K 同步服务（P1.2）。
 *
 * <p>数据流：Java 判定标的 → Python 抓取 → Java 校验/过滤 → UPSERT 落库。
 * <p>业务取舍全部在这里，Python 侧只做搬运：
 * <ul>
 *   <li>未收盘的当日 bar 拒写（上游盘中会返回半根 K 线）</li>
 *   <li>非正价格拒写（前复权长窗口会把早期价格算成负数）</li>
 * </ul>
 *
 * <p>失败隔离：单个标的失败只产生一条 FAILED 记录，不抛异常、不影响其他标的，
 * 也不影响 P0 的实时采集与告警。
 */
@Service
public class KlineSyncService {

    private static final Logger log = LoggerFactory.getLogger(KlineSyncService.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    public static final String DEFAULT_ADJUST = "qfq";
    public static final int MAX_LIMIT = 500;
    private static final Set<String> SUPPORTED_ADJUST = Set.of("qfq", "hfq", "raw");

    private final KlineDailyMapper klineMapper;
    private final HistoryDataClient historyClient;
    private final TradingCalendarService calendar;
    private final InstrumentMapper instrumentMapper;
    private final WatchlistMapper watchlistMapper;
    private final MonitorTargetMapper monitorTargetMapper;
    private final PositionService positionService;
    private final JobRunStats jobStats;
    private final LocalTime closeTime;

    public KlineSyncService(KlineDailyMapper klineMapper,
                            HistoryDataClient historyClient,
                            TradingCalendarService calendar,
                            InstrumentMapper instrumentMapper,
                            WatchlistMapper watchlistMapper,
                            MonitorTargetMapper monitorTargetMapper,
                            PositionService positionService,
                            JobRunStats jobStats,
                            @Value("${stockcat.history.close-time:15:05}") String closeTime) {
        this.klineMapper = klineMapper;
        this.historyClient = historyClient;
        this.calendar = calendar;
        this.instrumentMapper = instrumentMapper;
        this.watchlistMapper = watchlistMapper;
        this.monitorTargetMapper = monitorTargetMapper;
        this.positionService = positionService;
        this.jobStats = jobStats;
        this.closeTime = LocalTime.parse(closeTime);
    }

    /**
     * 同步单个标的。instrument 不存在抛 404；其余失败都体现在返回结果的 status/code 里。
     */
    public KlineSyncSummary syncInstrument(Long instrumentId, int days, String adjust) {
        long start = System.currentTimeMillis();
        String normalizedAdjust = normalizeAdjust(adjust);
        int limit = clampLimit(days);

        Instrument instrument = instrumentMapper.findById(instrumentId);
        if (instrument == null) {
            throw ApiException.notFound("标的不存在: id=" + instrumentId);
        }
        String secid = SymbolMapping.cnAToSecid(instrument.getSymbol());
        if (secid == null) {
            // 北交所 8xx/4xx 等当前规则不覆盖，明确拒绝而不是猜一个 secid
            return KlineSyncSummary.skipped(instrumentId, instrument.getSymbol(), normalizedAdjust,
                    "UNSUPPORTED_SYMBOL", "代码不在沪深规则内，未同步", elapsed(start));
        }

        LocalDate lastClosed = lastCompletedTradingDate(OffsetDateTime.now(CN));
        try {
            KlineFetch fetch = historyClient.fetch(secid, normalizedAdjust, limit, lastClosed);
            if (fetch.bars().isEmpty()) {
                return KlineSyncSummary.failed(instrumentId, instrument.getSymbol(), normalizedAdjust,
                        "NO_DATA", "上游未返回日K数据（截至 " + lastClosed + "）", elapsed(start));
            }

            FilterOutcome outcome = filter(fetch.bars(), lastClosed);
            String sourceCode = fetch.source() == null ? HistoryDataClient.SOURCE_CODE : fetch.source();
            for (DailyBar bar : outcome.accepted()) {
                klineMapper.upsert(toRow(instrumentId, normalizedAdjust, bar, sourceCode));
            }

            log.info("历史同步 {} 截至 {}：写入 {} 拒写(未收盘) {} 拒写(脏行) {} 上游跳过 {}",
                    instrument.getSymbol(), lastClosed, outcome.accepted().size(),
                    outcome.rejectedUnclosed(), outcome.rejectedInvalid(), fetch.skippedBars());

            return new KlineSyncSummary(
                    instrumentId, instrument.getSymbol(), normalizedAdjust,
                    outcome.accepted().isEmpty() ? "FAILED" : "SUCCESS",
                    outcome.accepted().isEmpty() ? "NO_USABLE_DATA" : null,
                    outcome.accepted().isEmpty() ? "上游数据全部未通过校验（未收盘或价格非正）" : null,
                    limit, outcome.accepted().size(),
                    outcome.rejectedUnclosed(), outcome.rejectedInvalid(), fetch.skippedBars(),
                    sourceCode, elapsed(start));
        } catch (HistorySourceException e) {
            log.warn("历史同步失败 {}: [{}] {}", instrument.getSymbol(), e.getCode(), e.getMessage());
            // 带上 secid：上游的「参数不合法」这类消息不含代码，排障时必须能定位到具体标的
            return KlineSyncSummary.failed(instrumentId, instrument.getSymbol(), normalizedAdjust,
                    e.getCode(), e.getMessage() + "（secid=" + secid + "）", elapsed(start));
        } catch (Exception e) {
            // 落库异常等：记录但不外抛，避免一个标的打断整批同步
            log.error("历史同步异常 {}: {}", instrument.getSymbol(), e.getMessage(), e);
            return KlineSyncSummary.failed(instrumentId, instrument.getSymbol(), normalizedAdjust,
                    "SYNC_ERROR", "同步异常: " + e.getMessage(), elapsed(start));
        }
    }

    /**
     * 批量同步：自选 ∪ 启用的监控标的 ∪ 当前持仓（与 P0 采集范围一致）。
     */
    public KlineSyncBatchSummary syncAll(int days) {
        long start = System.currentTimeMillis();
        List<Long> ids = collectScopeInstrumentIds();

        List<KlineSyncSummary> results = new ArrayList<>();
        for (Long id : ids) {
            try {
                results.add(syncInstrument(id, days, DEFAULT_ADJUST));
            } catch (Exception e) {
                log.error("批量同步中标的 {} 失败: {}", id, e.getMessage(), e);
                results.add(KlineSyncSummary.failed(id, null, DEFAULT_ADJUST, "SYNC_ERROR",
                        "同步异常: " + e.getMessage(), 0));
            }
        }
        int succeeded = (int) results.stream().filter(r -> "SUCCESS".equals(r.status())).count();
        int skipped = (int) results.stream().filter(r -> "SKIPPED".equals(r.status())).count();
        int failed = results.size() - succeeded - skipped;
        long cost = elapsed(start);
        log.info("历史同步完成：标的 {} 成功 {} 失败 {} 跳过 {}，耗时 {}ms",
                ids.size(), succeeded, failed, skipped, cost);
        String text = "标的 %d 成功 %d 失败 %d 跳过 %d".formatted(ids.size(), succeeded, failed, skipped);
        // 统计记在 service 而不是定时器里：手动触发（POST /history/sync）同样要反映到系统状态页，
        // 否则"刚点过同步，页面还写着未运行"，用户只会以为按钮没生效。
        // 全部失败不记成功——那是最容易被忽略的坏状态。
        if (succeeded == 0 && failed > 0) {
            jobStats.recordFailure(JobRunStats.HISTORY_SYNC, text);
        } else {
            jobStats.recordSuccess(JobRunStats.HISTORY_SYNC, text);
        }
        return new KlineSyncBatchSummary(ids.size(), succeeded, failed, skipped, cost, results);
    }

    private List<Long> collectScopeInstrumentIds() {
        return scopeInstrumentIds();
    }

    /**
     * 同步范围：自选 ∪ 启用的监控标的 ∪ 当前持仓（与 P0 采集范围一致）。
     * 公开给基金净值同步复用，避免两处各写一套"哪些标的要同步"的逻辑。
     */
    public List<Long> scopeInstrumentIds() {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        watchlistMapper.selectAll().forEach(row -> ids.add(row.getInstrumentId()));
        monitorTargetMapper.selectAll().forEach(row -> {
            if (Boolean.TRUE.equals(row.getEnabled())) {
                ids.add(row.getInstrumentId());
            }
        });
        try {
            positionService.list().stream()
                    .filter(p -> p.quantity() != null && p.quantity().signum() > 0)
                    .forEach(p -> ids.add(p.instrumentId()));
        } catch (Exception e) {
            // 持仓聚合失败不该阻断自选/监控的同步
            log.warn("读取持仓失败，本次同步范围不含持仓: {}", e.getMessage());
        }
        return new ArrayList<>(ids);
    }

    /**
     * 过滤上游 bar：拒写未收盘的当日数据与非正价格。
     * 公开给单元测试，覆盖「上游给了什么」与「什么能入库」的边界。
     */
    public FilterOutcome filter(List<DailyBar> bars, LocalDate lastClosedTradingDate) {
        List<DailyBar> accepted = new ArrayList<>();
        int unclosed = 0;
        int invalid = 0;
        for (DailyBar bar : bars) {
            if (bar.tradeDate() == null) {
                invalid++;
                continue;
            }
            if (bar.tradeDate().isAfter(lastClosedTradingDate)) {
                unclosed++;
                continue;
            }
            if (!usable(bar)) {
                invalid++;
                continue;
            }
            accepted.add(bar);
        }
        return new FilterOutcome(accepted, unclosed, invalid);
    }

    private static boolean usable(DailyBar bar) {
        return positive(bar.open()) && positive(bar.high()) && positive(bar.low()) && positive(bar.close())
                && bar.high() >= bar.low();
    }

    private static boolean positive(Double value) {
        return value != null && value > 0;
    }

    /**
     * 最近一个「已收盘」的交易日（Asia/Shanghai）。
     * 交易日且已过收盘时刻 → 今天；否则回溯到上一个交易日。
     */
    public LocalDate lastCompletedTradingDate(OffsetDateTime now) {
        var local = now.atZoneSameInstant(CN);
        LocalDate today = local.toLocalDate();
        if (calendar.isTradingDay(today) && !local.toLocalTime().isBefore(closeTime)) {
            return today;
        }
        LocalDate candidate = today.minusDays(1);
        for (int i = 0; i < 30; i++) {
            if (calendar.isTradingDay(candidate)) {
                return candidate;
            }
            candidate = candidate.minusDays(1);
        }
        log.warn("回溯 30 天未找到交易日，回退到 {}", today.minusDays(1));
        return today.minusDays(1);
    }

    /** 校验并归一化复权口径；非法值返回 400，不做「猜一个」的兜底。 */
    public static String normalizeAdjust(String adjust) {
        if (adjust == null || adjust.isBlank()) {
            return DEFAULT_ADJUST;
        }
        String value = adjust.trim().toLowerCase();
        if (!SUPPORTED_ADJUST.contains(value)) {
            throw ApiException.validationError("adjust 仅支持 qfq / hfq / raw，收到: " + adjust);
        }
        return value;
    }

    public static int clampLimit(int days) {
        if (days <= 0) {
            return 1;
        }
        return Math.min(days, MAX_LIMIT);
    }

    private KlineDaily toRow(Long instrumentId, String adjust, DailyBar bar, String sourceCode) {
        KlineDaily row = new KlineDaily();
        row.setInstrumentId(instrumentId);
        row.setTradeDate(bar.tradeDate());
        row.setAdjust(adjust);
        row.setOpen(bar.open());
        row.setHigh(bar.high());
        row.setLow(bar.low());
        row.setClose(bar.close());
        row.setVolume(bar.volume());
        row.setAmount(bar.amount());
        row.setChangePercent(bar.changePercent());
        row.setTurnoverRate(bar.turnoverRate());
        row.setSourceCode(sourceCode);
        return row;
    }

    private static long elapsed(long startMillis) {
        return System.currentTimeMillis() - startMillis;
    }

    /** accepted 可入库；rejectedUnclosed 未收盘当日；rejectedInvalid 脏行。 */
    public record FilterOutcome(List<DailyBar> accepted, int rejectedUnclosed, int rejectedInvalid) {
    }
}
