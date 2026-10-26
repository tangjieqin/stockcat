package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Instrument;
import com.stockcat.domain.PositionSnapshot;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.mapper.PositionSnapshotMapper;
import com.stockcat.mapper.TradeMapper;
import com.stockcat.web.dto.EquityCurvePoint;
import com.stockcat.web.dto.EquityCurveResponse;
import com.stockcat.web.dto.EquityCurveSummary;
import com.stockcat.web.dto.PositionChangeResponse;
import org.springframework.stereotype.Service;

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
import java.util.TreeMap;

/**
 * 组合层查询（P1.5）：收益曲线与持仓变化。
 *
 * <p>全部由 position_snapshot 聚合，不额外建"组合日表"——那会变成第三份事实。
 * 组合级指标随时可以由快照重算，而快照随时可以由流水重算。
 *
 * <p><b>收益口径（有意保守）</b>：总盈亏 = 当前市值 − 净投入资金，收益率 = 总盈亏 / 净投入。
 * 这是简单收益，不是时间加权（TWR）：区间内追加/取出一笔钱会直接改变分母，
 * 页面必须把这句话原样显示出来，否则很容易被当成"基金净值增长率"来读。
 */
@Service
public class PortfolioService {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_LOOKBACK_DAYS = 180;
    private static final int MAX_SPAN_DAYS = 365 * 3;
    private static final int PCT_SCALE = 2;

    public static final String NOTE = "简单收益：总盈亏 = 当前市值 − 净投入资金（买入含费用、卖出扣费用）；"
            + "收盘价用不复权口径，未调整分红/送股，也未做资金加权（TWR）——"
            + "区间内追加或取出一笔钱，收益率的分母会跟着变。";

    private final PositionSnapshotMapper snapshotMapper;
    private final TradeMapper tradeMapper;
    private final InstrumentMapper instrumentMapper;

    public PortfolioService(PositionSnapshotMapper snapshotMapper, TradeMapper tradeMapper,
                            InstrumentMapper instrumentMapper) {
        this.snapshotMapper = snapshotMapper;
        this.tradeMapper = tradeMapper;
        this.instrumentMapper = instrumentMapper;
    }

    // ---------------------------------------------------------------- 收益曲线

    public EquityCurveResponse equityCurve(LocalDate from, LocalDate to) {
        LocalDate lastSnapshotDate = snapshotMapper.findLatestDate();
        Range range = resolveRange(from, to, lastSnapshotDate);

        List<PositionSnapshot> rows = snapshotMapper.selectRange(range.from, range.to);
        Map<LocalDate, DayAggregate> byDate = new TreeMap<>();
        for (PositionSnapshot row : rows) {
            if (row.getTradeDate() == null) {
                continue;
            }
            byDate.computeIfAbsent(row.getTradeDate(), d -> new DayAggregate()).add(row);
        }

        List<EquityCurvePoint> points = new ArrayList<>();
        for (Map.Entry<LocalDate, DayAggregate> entry : byDate.entrySet()) {
            DayAggregate agg = entry.getValue();
            points.add(new EquityCurvePoint(entry.getKey(), agg.marketValue, agg.costValue,
                    agg.unrealized, agg.realized, agg.totalPnl(), agg.positions, agg.priced, agg.complete));
        }

        return new EquityCurveResponse(range.from, range.to, points,
                summarize(points, range.to), lastSnapshotDate, PositionSnapshotService.PRICE_ADJUST, NOTE);
    }

    private EquityCurveSummary summarize(List<EquityCurvePoint> points, LocalDate to) {
        BigDecimal netInvested = tradeMapper.netInvestedUntil(endOfDay(to));
        if (points.isEmpty()) {
            return new EquityCurveSummary(null, null, null, null, null, null, null, null, null,
                    netInvested, null);
        }
        EquityCurvePoint first = points.get(0);
        EquityCurvePoint last = points.get(points.size() - 1);
        BigDecimal change = first.marketValue() != null && last.marketValue() != null
                ? last.marketValue().subtract(first.marketValue())
                : null;
        BigDecimal returnPct = null;
        // 最后一天缺价时不给收益率：分母是净投入，分子却是"只算到能取到价的部分"，比出来没有意义
        if (last.complete() && netInvested != null && netInvested.signum() > 0 && last.totalPnl() != null) {
            returnPct = last.totalPnl()
                    .divide(netInvested, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(PCT_SCALE, RoundingMode.HALF_UP);
        }
        return new EquityCurveSummary(first.tradeDate(), last.tradeDate(), first.marketValue(),
                last.marketValue(), change, last.costValue(), last.unrealizedPnl(), last.realizedPnlCum(),
                last.totalPnl(), netInvested, returnPct);
    }

    // ---------------------------------------------------------------- 持仓变化

    public List<PositionChangeResponse> positionChanges(LocalDate from, LocalDate to) {
        LocalDate lastSnapshotDate = snapshotMapper.findLatestDate();
        Range range = resolveRange(from, to, lastSnapshotDate);

        List<PositionSnapshot> rows = snapshotMapper.selectRange(range.from, range.to);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, PositionSnapshot> baseline = new HashMap<>();
        for (PositionSnapshot row : snapshotMapper.selectLatestBefore(range.from)) {
            baseline.put(row.getInstrumentId(), row);
        }

        Map<Long, List<PositionSnapshot>> byInstrument = new LinkedHashMap<>();
        for (PositionSnapshot row : rows) {
            byInstrument.computeIfAbsent(row.getInstrumentId(), k -> new ArrayList<>()).add(row);
        }

        List<Instrument> instruments = instrumentMapper.selectByIds(new ArrayList<>(byInstrument.keySet()));
        Map<Long, Instrument> instrumentById = new HashMap<>();
        for (Instrument instrument : instruments) {
            instrumentById.put(instrument.getId(), instrument);
        }

        OffsetDateTime rangeStart = startOfDay(range.from);
        OffsetDateTime rangeEnd = endOfDay(range.to);
        List<PositionChangeResponse> changes = new ArrayList<>();
        for (Map.Entry<Long, List<PositionSnapshot>> entry : byInstrument.entrySet()) {
            Long id = entry.getKey();
            List<PositionSnapshot> series = entry.getValue();
            PositionSnapshot open = baseline.get(id);
            PositionSnapshot close = series.get(series.size() - 1);
            Instrument instrument = instrumentById.get(id);

            BigDecimal openQty = open == null ? BigDecimal.ZERO : open.getQuantity();
            BigDecimal closeQty = close.getQuantity();
            BigDecimal openRealized = open == null ? BigDecimal.ZERO : nz(open.getRealizedPnlCum());
            // 期初没有持仓时：市值/浮盈确实是 0（不是"不知道"），只有"每股成本"没有定义
            BigDecimal openMarketValue = open == null ? BigDecimal.ZERO : open.getMarketValue();
            BigDecimal openUnrealized = open == null ? BigDecimal.ZERO : open.getUnrealizedPnl();

            changes.add(new PositionChangeResponse(
                    id,
                    instrument == null ? null : instrument.getSymbol(),
                    instrument == null ? null : instrument.getName(),
                    open == null ? null : open.getTradeDate(),
                    close.getTradeDate(),
                    openQty, closeQty, closeQty.subtract(openQty),
                    open == null ? null : open.getAvgCost(), close.getAvgCost(),
                    open == null ? null : delta(open.getAvgCost(), close.getAvgCost()),
                    openMarketValue, close.getMarketValue(),
                    delta(openMarketValue, close.getMarketValue()),
                    openUnrealized, close.getUnrealizedPnl(),
                    delta(openUnrealized, close.getUnrealizedPnl()),
                    nz(close.getRealizedPnlCum()).subtract(openRealized),
                    nz(close.getRealizedPnlCum()),
                    tradeMapper.countInRange(id, rangeStart, rangeEnd),
                    closeQty.signum() == 0));
        }
        return changes;
    }

    // ---------------------------------------------------------------- 内部

    private record Range(LocalDate from, LocalDate to) {
    }

    private Range resolveRange(LocalDate from, LocalDate to, LocalDate lastSnapshotDate) {
        LocalDate end = to != null ? to : (lastSnapshotDate != null ? lastSnapshotDate : LocalDate.now(CN));
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_LOOKBACK_DAYS);
        if (start.isAfter(end)) {
            throw ApiException.validationError("from 不能晚于 to（from=" + start + ", to=" + end + "）");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_SPAN_DAYS) {
            throw ApiException.validationError("区间不能超过 3 年（" + MAX_SPAN_DAYS + " 天）");
        }
        return new Range(start, end);
    }

    private static BigDecimal delta(BigDecimal open, BigDecimal close) {
        if (open == null || close == null) {
            return null;
        }
        return close.subtract(open);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static OffsetDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay(CN).toOffsetDateTime();
    }

    private static OffsetDateTime endOfDay(LocalDate date) {
        return date.atTime(23, 59, 59).atZone(CN).toOffsetDateTime();
    }

    /** 单日汇总。complete=false 表示有持仓缺收盘价，市值与浮盈只是"能取到价的部分"。 */
    private static final class DayAggregate {
        private BigDecimal marketValue = BigDecimal.ZERO;
        private BigDecimal costValue = BigDecimal.ZERO;
        private BigDecimal unrealized = BigDecimal.ZERO;
        private BigDecimal realized = BigDecimal.ZERO;
        private int positions;
        private int priced;
        private boolean complete = true;

        void add(PositionSnapshot row) {
            costValue = costValue.add(nz(row.getCostValue()));
            realized = realized.add(nz(row.getRealizedPnlCum()));
            if (row.getQuantity() == null || row.getQuantity().signum() <= 0) {
                return; // 清仓行只贡献成本(0)与已实现
            }
            positions++;
            if (row.getMarketValue() == null) {
                complete = false;
                return;
            }
            marketValue = marketValue.add(row.getMarketValue());
            priced++;
            if (row.getUnrealizedPnl() != null) {
                unrealized = unrealized.add(row.getUnrealizedPnl());
            }
        }

        BigDecimal totalPnl() {
            return unrealized.add(realized);
        }
    }
}
