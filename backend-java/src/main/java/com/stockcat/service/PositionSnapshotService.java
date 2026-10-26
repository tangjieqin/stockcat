package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.KlineDaily;
import com.stockcat.domain.PositionSnapshot;
import com.stockcat.domain.Trade;
import com.stockcat.mapper.KlineDailyMapper;
import com.stockcat.mapper.PositionSnapshotMapper;
import com.stockcat.mapper.TradeMapper;
import com.stockcat.web.dto.KlineSyncSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 持仓快照（P1.5）：按 trade_record 重放 + 当日收盘价，生成"每个交易日收盘时的持仓"。
 *
 * <p>三条设计约束：
 * <ol>
 *   <li><b>快照不是事实来源</b>。事实是流水与收盘价，快照随时可整段删除重建，
 *       所以重建 = 先删区间再插，不做增量修补——增量修补迟早会和重放结果对不上。</li>
 *   <li><b>必须用不复权收盘价</b>。avg_cost 是真实成交价（含费用），前复权价随除权整体下移，
 *       两者直接相减会算出"每年都亏一笔分红"的假曲线。宁可缺价（留 null + 告警），也不混口径。</li>
 *   <li><b>缺价就留 null</b>。市值/浮盈算不出来时留 null，由上层标 complete=false，
 *       不按 0 汇总——那会让整条曲线在某天突然塌下去。</li>
 * </ol>
 *
 * <p>日期轴取"该标的实际有收盘价的交易日 ∪ 有成交的日期"，而不是工作日历：
 * 这样停牌日自然沿用前收（floor），节假日不会凭空多出点。
 */
@Service
public class PositionSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(PositionSnapshotService.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    /** 收盘价口径。raw = 不复权，能与成交价比较；见类注释第 2 条。 */
    public static final String PRICE_ADJUST = "raw";

    private static final int VALUE_SCALE = 4;
    private static final int MAX_SPAN_DAYS = 365 * 3;
    private static final int BATCH_SIZE = 500;

    private final TradeMapper tradeMapper;
    private final KlineDailyMapper klineMapper;
    private final PositionSnapshotMapper snapshotMapper;
    private final HoldingComputationService computation;
    private final KlineSyncService klineSyncService;

    public PositionSnapshotService(TradeMapper tradeMapper,
                                   KlineDailyMapper klineMapper,
                                   PositionSnapshotMapper snapshotMapper,
                                   HoldingComputationService computation,
                                   KlineSyncService klineSyncService) {
        this.tradeMapper = tradeMapper;
        this.klineMapper = klineMapper;
        this.snapshotMapper = snapshotMapper;
        this.computation = computation;
        this.klineSyncService = klineSyncService;
    }

    /**
     * 重建 [from, to] 区间的全部快照。
     *
     * @param ensurePrices true 时先补齐不复权日线（缺就调 Python 同步）；手动重建与定时任务都传 true，
     *                     单元测试传 false 以便纯逻辑验证
     */
    @Transactional
    public RebuildResult rebuild(LocalDate from, LocalDate to, boolean ensurePrices) {
        long start = System.currentTimeMillis();
        if (from == null || to == null) {
            throw ApiException.validationError("from 与 to 不能为空");
        }
        if (to.isBefore(from)) {
            throw ApiException.validationError("to 不能早于 from");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_SPAN_DAYS) {
            throw ApiException.validationError("单次重建区间不能超过 3 年（" + MAX_SPAN_DAYS + " 天）");
        }

        List<String> warnings = new ArrayList<>();
        List<Long> instrumentIds = tradeMapper.selectDistinctInstrumentIds();
        if (instrumentIds.isEmpty()) {
            snapshotMapper.deleteRange(from, to);
            return new RebuildResult(from, to, 0, 0, 0, 0,
                    List.of("当前没有任何交易流水，无需生成快照"), elapsed(start));
        }

        int synced = ensurePrices ? ensureRawCloses(instrumentIds, from, to, warnings) : 0;

        // ---- 读入：流水（全量，含区间之前的，才能还原期初持仓）与收盘价
        Map<Long, List<Trade>> tradesById = new LinkedHashMap<>();
        for (Trade trade : tradeMapper.selectAllOrderByTime()) {
            if (trade.getInstrumentId() == null) {
                continue;
            }
            tradesById.computeIfAbsent(trade.getInstrumentId(), k -> new ArrayList<>()).add(trade);
        }

        Map<Long, CloseSeries> closesById = new HashMap<>();
        for (Long id : instrumentIds) {
            List<KlineDaily> bars = klineMapper.selectUpTo(id, PRICE_ADJUST, to);
            if (bars.isEmpty()) {
                warnings.add(id + " 没有不复权日线，市值/浮盈留空；请先同步历史 K 线");
                continue;
            }
            closesById.put(id, new CloseSeries(bars));
        }

        // ---- 日期轴：有收盘价的交易日 ∪ 有成交的日期（都限定在区间内）
        TreeSet<LocalDate> axis = new TreeSet<>();
        for (CloseSeries series : closesById.values()) {
            for (LocalDate date : series.closes.keySet()) {
                if (!date.isBefore(from) && !date.isAfter(to)) {
                    axis.add(date);
                }
            }
        }
        Map<Long, Walker> walkers = new LinkedHashMap<>();
        for (Long id : instrumentIds) {
            List<Trade> trades = tradesById.getOrDefault(id, List.of());
            walkers.put(id, new Walker(trades));
            for (Trade trade : trades) {
                LocalDate date = dateOf(trade);
                if (!date.isBefore(from) && !date.isAfter(to)) {
                    axis.add(date);
                }
            }
        }

        // ---- 逐日推进（按日期升序，每个标的一次线性前进）
        List<PositionSnapshot> rows = new ArrayList<>();
        int dates = 0;
        for (LocalDate date : axis) {
            boolean any = false;
            for (Long id : instrumentIds) {
                Walker walker = walkers.get(id);
                walker.advanceTo(date, computation);
                HoldingComputationService.HoldingState state = walker.state();
                boolean tradedToday = walker.tradedOn(date);
                // 已清仓且当天没交易：不写行（否则清仓后每天一行垃圾）
                if (state.quantity().signum() == 0 && !tradedToday) {
                    continue;
                }
                rows.add(row(date, id, state, closesById.get(id)));
                any = true;
            }
            if (any) {
                dates++;
            }
        }

        snapshotMapper.deleteRange(from, to);
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            snapshotMapper.insertBatch(rows.subList(i, Math.min(i + BATCH_SIZE, rows.size())));
        }

        long elapsed = elapsed(start);
        log.info("持仓快照重建 {}~{}：标的 {} 交易日 {} 行 {} 补价标的 {} 耗时 {}ms",
                from, to, instrumentIds.size(), dates, rows.size(), synced, elapsed);
        return new RebuildResult(from, to, dates, instrumentIds.size(), rows.size(), synced, warnings, elapsed);
    }

    /**
     * 补齐不复权日线：根数不够或最新一天明显落后时才同步，避免每次重建都打一遍上游。
     * 返回实际发起同步的标的数（不论成败）。
     */
    private int ensureRawCloses(List<Long> instrumentIds, LocalDate from, LocalDate to, List<String> warnings) {
        int wantBars = desiredBars(from, to);
        LocalDate freshness = to.minusDays(3); // 周末/节假日回退：3 天内的最新一根算"够新"
        int attempted = 0;
        for (Long id : instrumentIds) {
            try {
                long have = klineMapper.countByInstrument(id, PRICE_ADJUST);
                LocalDate latest = klineMapper.findLatestTradeDate(id, PRICE_ADJUST);
                boolean tooFew = have < wantBars;
                boolean tooOld = latest == null || latest.isBefore(freshness);
                if (!tooFew && !tooOld) {
                    continue;
                }
                attempted++;
                KlineSyncSummary summary = klineSyncService.syncInstrument(id, wantBars, PRICE_ADJUST);
                if (!"SUCCESS".equals(summary.status())) {
                    warnings.add("标的 " + id + " 不复权日线同步失败（" + summary.code() + "）：" + summary.message()
                            + "；该标的市值可能留空");
                    if (isSourceDown(summary.code())) {
                        // 数据源整体不可用，剩下的标的没必要再等一遍超时
                        warnings.add("历史数据源不可用，已跳过其余标的的不复权日线同步");
                        break;
                    }
                }
            } catch (Exception e) {
                warnings.add("标的 " + id + " 不复权日线同步异常：" + e.getMessage());
                log.warn("补齐不复权日线失败 instrument={}: {}", id, e.getMessage());
            }
        }
        return attempted;
    }

    private static boolean isSourceDown(String code) {
        return code != null && (code.startsWith("DATA_SOURCE") || code.startsWith("UPSTREAM"));
    }

    /** 需要多少根日线才能覆盖 [from, to]：按一周 5 个交易日粗略折算，多留 30 根余量。 */
    static int desiredBars(LocalDate from, LocalDate to) {
        long days = Math.max(1, ChronoUnit.DAYS.between(from, to));
        return (int) Math.min(500, Math.max(40, days * 5 / 7 + 30));
    }

    private static PositionSnapshot row(LocalDate date, Long instrumentId,
                                        HoldingComputationService.HoldingState state, CloseSeries series) {
        PositionSnapshot row = new PositionSnapshot();
        row.setTradeDate(date);
        row.setInstrumentId(instrumentId);
        row.setQuantity(state.quantity());
        row.setAvgCost(state.avgCost());
        row.setRealizedPnlCum(state.realizedPnl().setScale(VALUE_SCALE, RoundingMode.HALF_UP));

        BigDecimal cost = state.quantity().multiply(state.avgCost())
                .setScale(VALUE_SCALE, RoundingMode.HALF_UP);
        row.setCostValue(cost);
        row.setPriceAdjust(PRICE_ADJUST);
        row.setSourceCode(series == null ? null : series.sourceCode);

        if (state.quantity().signum() == 0) {
            // 清仓日：市值确实是 0，不是"不知道"
            row.setClosePrice(series == null ? null : series.closeOn(date));
            row.setMarketValue(BigDecimal.ZERO.setScale(VALUE_SCALE, RoundingMode.HALF_UP));
            row.setUnrealizedPnl(BigDecimal.ZERO.setScale(VALUE_SCALE, RoundingMode.HALF_UP));
            return row;
        }
        BigDecimal close = series == null ? null : series.closeOn(date);
        if (close == null) {
            return row; // 缺价：close/marketValue/unrealized 全留 null
        }
        BigDecimal marketValue = state.quantity().multiply(close).setScale(VALUE_SCALE, RoundingMode.HALF_UP);
        row.setClosePrice(close);
        row.setMarketValue(marketValue);
        row.setUnrealizedPnl(marketValue.subtract(cost));
        return row;
    }

    static LocalDate dateOf(Trade trade) {
        OffsetDateTime time = trade.getTradeTime();
        return time == null ? null : time.atZoneSameInstant(CN).toLocalDate();
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /** 某标的的收盘价序列：支持"取当日，停牌则取最近一个交易日"。 */
    static final class CloseSeries {
        private final NavigableMap<LocalDate, BigDecimal> closes = new TreeMap<>();
        private final String sourceCode;

        CloseSeries(List<KlineDaily> bars) {
            String source = null;
            for (KlineDaily bar : bars) {
                if (bar.getClose() == null || bar.getTradeDate() == null) {
                    continue;
                }
                closes.put(bar.getTradeDate(), BigDecimal.valueOf(bar.getClose()));
                source = bar.getSourceCode();
            }
            this.sourceCode = source;
        }

        BigDecimal closeOn(LocalDate date) {
            Map.Entry<LocalDate, BigDecimal> entry = closes.floorEntry(date);
            return entry == null ? null : entry.getValue();
        }
    }

    /** 单标的的流水游标：按日期推进，避免对每个日期都把全部流水重放一遍。 */
    static final class Walker {
        private final List<Trade> trades;
        private final Map<LocalDate, Integer> countByDate = new HashMap<>();
        private int index;
        private HoldingComputationService.HoldingState state = HoldingComputationService.HoldingState.EMPTY;

        Walker(List<Trade> trades) {
            this.trades = trades;
            for (Trade trade : trades) {
                LocalDate date = dateOf(trade);
                if (date != null) {
                    countByDate.merge(date, 1, Integer::sum);
                }
            }
        }

        void advanceTo(LocalDate date, HoldingComputationService computation) {
            while (index < trades.size()) {
                LocalDate tradeDate = dateOf(trades.get(index));
                if (tradeDate != null && tradeDate.isAfter(date)) {
                    break;
                }
                state = computation.apply(state, trades.get(index));
                index++;
            }
        }

        HoldingComputationService.HoldingState state() {
            return state;
        }

        boolean tradedOn(LocalDate date) {
            return countByDate.getOrDefault(date, 0) > 0;
        }
    }

    /** 重建结果（日志与接口共用）。 */
    public record RebuildResult(LocalDate from, LocalDate to, int tradeDates, int instruments, int rows,
                                int syncedInstruments, List<String> warnings, long elapsedMs) {
    }
}
