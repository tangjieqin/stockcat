package com.stockcat.service;

import com.stockcat.domain.KlineDaily;
import com.stockcat.domain.PositionSnapshot;
import com.stockcat.domain.Trade;
import com.stockcat.mapper.KlineDailyMapper;
import com.stockcat.mapper.PositionSnapshotMapper;
import com.stockcat.mapper.TradeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1.5 按已知流水手算对齐（多笔买入、部分卖出、清仓）：
 * 每日快照的数量/摊薄成本/市值/浮盈/累计已实现必须与手算完全一致。
 */
class PositionSnapshotServiceTest {

    private TradeMapper tradeMapper;
    private KlineDailyMapper klineMapper;
    private PositionSnapshotMapper snapshotMapper;
    private KlineSyncService klineSyncService;
    private PositionSnapshotService service;

    private static final LocalDate D20 = LocalDate.parse("2026-10-20");
    private static final LocalDate D21 = LocalDate.parse("2026-10-21");
    private static final LocalDate D22 = LocalDate.parse("2026-10-22");
    private static final LocalDate D23 = LocalDate.parse("2026-10-23");
    private static final LocalDate D26 = LocalDate.parse("2026-10-26");

    @BeforeEach
    void setup() {
        tradeMapper = mock(TradeMapper.class);
        klineMapper = mock(KlineDailyMapper.class);
        snapshotMapper = mock(PositionSnapshotMapper.class);
        klineSyncService = mock(KlineSyncService.class);
        service = new PositionSnapshotService(tradeMapper, klineMapper, snapshotMapper,
                new HoldingComputationService(), klineSyncService);
        when(snapshotMapper.insertBatch(anyList())).thenReturn(1);
    }

    private static Trade trade(long instrumentId, String direction, String price, String qty, String fee, LocalDate date) {
        Trade t = new Trade();
        t.setInstrumentId(instrumentId);
        t.setDirection(direction);
        t.setPrice(new BigDecimal(price));
        t.setQuantity(new BigDecimal(qty));
        t.setFee(new BigDecimal(fee));
        // 收盘后 14:30 成交，落在同一交易日
        t.setTradeTime(date.atTime(14, 30).atZone(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime());
        return t;
    }

    private static KlineDaily bar(long instrumentId, LocalDate date, String close) {
        KlineDaily bar = new KlineDaily();
        bar.setInstrumentId(instrumentId);
        bar.setTradeDate(date);
        bar.setAdjust("raw");
        bar.setClose(Double.valueOf(close));
        bar.setSourceCode("eastmoney-push2his");
        return bar;
    }

    @SuppressWarnings("unchecked")
    private List<PositionSnapshot> capturedRows() {
        ArgumentCaptor<List<PositionSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(snapshotMapper, org.mockito.Mockito.atLeastOnce()).insertBatch(captor.capture());
        List<PositionSnapshot> all = new ArrayList<>();
        captor.getAllValues().forEach(all::addAll);
        return all;
    }

    private static PositionSnapshot find(List<PositionSnapshot> rows, long instrumentId, LocalDate date) {
        return rows.stream()
                .filter(r -> r.getInstrumentId() == instrumentId && date.equals(r.getTradeDate()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void replaysTradesIntoDailySnapshots() {
        when(tradeMapper.selectDistinctInstrumentIds()).thenReturn(List.of(1L, 2L));
        when(tradeMapper.selectAllOrderByTime()).thenReturn(List.of(
                // 标的 1：买 100@10(费用5) → 卖 40@12(费用2) → 卖 60@9(费用1) 清仓
                trade(1L, "BUY", "10", "100", "5", D20),
                trade(1L, "SELL", "12", "40", "2", D22),
                trade(1L, "SELL", "9", "60", "1", D23),
                // 标的 2：10-21 买 100@20（无费用）
                trade(2L, "BUY", "20", "100", "0", D21)));
        when(klineMapper.selectUpTo(eq(1L), eq("raw"), any())).thenReturn(List.of(
                bar(1L, D20, "10.2"), bar(1L, D21, "11"), bar(1L, D22, "12.5"),
                bar(1L, D23, "9"), bar(1L, D26, "9.5")));
        // 标的 2 故意缺 10-22 的 K 线：停牌日应沿用前收（10-21 的 18）
        when(klineMapper.selectUpTo(eq(2L), eq("raw"), any())).thenReturn(List.of(
                bar(2L, D20, "19"), bar(2L, D21, "18"), bar(2L, D23, "20.5")));
        when(klineMapper.countByInstrument(1L, "raw")).thenReturn(120L);
        when(klineMapper.findLatestTradeDate(1L, "raw")).thenReturn(D26);
        when(klineMapper.countByInstrument(2L, "raw")).thenReturn(120L);
        when(klineMapper.findLatestTradeDate(2L, "raw")).thenReturn(D23);

        PositionSnapshotService.RebuildResult result = service.rebuild(D20, D26, false);

        List<PositionSnapshot> rows = capturedRows();
        assertThat(result.tradeDates()).isEqualTo(5); // 10-20 / 21 / 22 / 23 / 26
        // 标的 1 四行（含 10-23 清仓行）+ 标的 2 五行（10-20 买入后一直持有到 10-26）
        assertThat(result.rows()).isEqualTo(8);
        assertThat(result.instruments()).isEqualTo(2);

        // 10-20 买入当天：avg = (100×10 + 5) / 100 = 10.05
        PositionSnapshot a20 = find(rows, 1L, D20);
        assertThat(a20.getQuantity()).isEqualByComparingTo("100");
        assertThat(a20.getAvgCost()).isEqualByComparingTo("10.05");
        assertThat(a20.getCostValue()).isEqualByComparingTo("1005");
        assertThat(a20.getMarketValue()).isEqualByComparingTo("1020");
        assertThat(a20.getUnrealizedPnl()).isEqualByComparingTo("15");
        assertThat(a20.getRealizedPnlCum()).isEqualByComparingTo("0");

        // 10-21 涨到 11：市值 1100，浮盈 95
        assertThat(find(rows, 1L, D21).getUnrealizedPnl()).isEqualByComparingTo("95");

        // 10-22 卖 40@12 费用 2：已实现 = (12−10.05)×40 − 2 = 76
        PositionSnapshot a22 = find(rows, 1L, D22);
        assertThat(a22.getQuantity()).isEqualByComparingTo("60");
        assertThat(a22.getAvgCost()).isEqualByComparingTo("10.05");
        assertThat(a22.getRealizedPnlCum()).isEqualByComparingTo("76");
        assertThat(a22.getMarketValue()).isEqualByComparingTo("750");   // 60×12.5
        assertThat(a22.getUnrealizedPnl()).isEqualByComparingTo("147"); // 750−603

        // 10-23 清仓：已实现再 −64 → 累计 12；市值确实是 0
        PositionSnapshot a23 = find(rows, 1L, D23);
        assertThat(a23.getQuantity()).isEqualByComparingTo("0");
        assertThat(a23.getAvgCost()).isEqualByComparingTo("0");
        assertThat(a23.getRealizedPnlCum()).isEqualByComparingTo("12");
        assertThat(a23.getMarketValue()).isEqualByComparingTo("0");
        assertThat(a23.getUnrealizedPnl()).isEqualByComparingTo("0");

        // 10-26 已清仓且无交易：不写行
        assertThat(find(rows, 1L, D26)).isNull();

        // 标的 2 在 10-22 没有 K 线：沿用 10-21 的 18，而不是 0
        PositionSnapshot b22 = find(rows, 2L, D22);
        assertThat(b22.getClosePrice()).isEqualByComparingTo("18");
        assertThat(b22.getMarketValue()).isEqualByComparingTo("1800");
        assertThat(b22.getUnrealizedPnl()).isEqualByComparingTo("-200");

        // 每天都有 price_adjust，便于事后解释市值口径
        assertThat(rows).allMatch(r -> "raw".equals(r.getPriceAdjust()));
    }

    @Test
    void missingClosePriceLeavesMarketValueNull() {
        when(tradeMapper.selectDistinctInstrumentIds()).thenReturn(List.of(9L));
        when(tradeMapper.selectAllOrderByTime()).thenReturn(List.of(
                trade(9L, "BUY", "5", "1000", "0", D20)));
        when(klineMapper.selectUpTo(eq(9L), eq("raw"), any())).thenReturn(List.of());

        PositionSnapshotService.RebuildResult result = service.rebuild(D20, D23, false);

        List<PositionSnapshot> rows = capturedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getQuantity()).isEqualByComparingTo("1000");
        assertThat(rows.get(0).getMarketValue()).isNull();
        assertThat(rows.get(0).getUnrealizedPnl()).isNull();
        assertThat(result.warnings()).anyMatch(w -> w.contains("不复权日线"));
    }

    @Test
    void emptyLedgerOnlyClearsRange() {
        when(tradeMapper.selectDistinctInstrumentIds()).thenReturn(List.of());

        PositionSnapshotService.RebuildResult result = service.rebuild(D20, D26, false);

        verify(snapshotMapper).deleteRange(D20, D26);
        verify(snapshotMapper, never()).insertBatch(anyList());
        assertThat(result.rows()).isZero();
        assertThat(result.warnings()).hasSize(1);
    }

    @Test
    void rebuildDeletesRangeBeforeInserting() {
        when(tradeMapper.selectDistinctInstrumentIds()).thenReturn(List.of(1L));
        when(tradeMapper.selectAllOrderByTime()).thenReturn(List.of(trade(1L, "BUY", "10", "100", "0", D20)));
        when(klineMapper.selectUpTo(eq(1L), eq("raw"), any())).thenReturn(List.of(bar(1L, D20, "10")));

        service.rebuild(D20, D23, false);

        // 重建必须整段覆盖，不做增量修补
        verify(snapshotMapper).deleteRange(D20, D23);
    }

    @Test
    void desiredBarsGrowsWithRangeAndCapsAt500() {
        assertThat(PositionSnapshotService.desiredBars(D20, D23)).isEqualTo(40);           // 下限 40
        assertThat(PositionSnapshotService.desiredBars(D20, D20.plusDays(365))).isEqualTo(290);
        assertThat(PositionSnapshotService.desiredBars(D20, D20.plusDays(365 * 3))).isEqualTo(500);
    }
}
