package com.stockcat.service;

import com.stockcat.domain.Instrument;
import com.stockcat.domain.PositionSnapshot;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.mapper.PositionSnapshotMapper;
import com.stockcat.mapper.TradeMapper;
import com.stockcat.web.dto.EquityCurvePoint;
import com.stockcat.web.dto.EquityCurveResponse;
import com.stockcat.web.dto.PositionChangeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1.5 组合聚合：曲线端点值/累计口径/缺价标记，以及持仓变化的期初对齐。
 */
class PortfolioServiceTest {

    private PositionSnapshotMapper snapshotMapper;
    private TradeMapper tradeMapper;
    private InstrumentMapper instrumentMapper;
    private PortfolioService service;

    private static final LocalDate D20 = LocalDate.parse("2026-10-20");
    private static final LocalDate D21 = LocalDate.parse("2026-10-21");
    private static final LocalDate D22 = LocalDate.parse("2026-10-22");

    @BeforeEach
    void setup() {
        snapshotMapper = mock(PositionSnapshotMapper.class);
        tradeMapper = mock(TradeMapper.class);
        instrumentMapper = mock(InstrumentMapper.class);
        service = new PortfolioService(snapshotMapper, tradeMapper, instrumentMapper);
        when(snapshotMapper.findLatestDate()).thenReturn(D22);
        when(tradeMapper.netInvestedUntil(any())).thenReturn(new BigDecimal("1000"));
    }

    private static PositionSnapshot row(long instrumentId, LocalDate date, String qty, String avg,
                                        String marketValue, String unrealized, String realizedCum) {
        PositionSnapshot r = new PositionSnapshot();
        r.setInstrumentId(instrumentId);
        r.setTradeDate(date);
        r.setQuantity(new BigDecimal(qty));
        r.setAvgCost(new BigDecimal(avg));
        r.setCostValue(new BigDecimal(qty).multiply(new BigDecimal(avg)));
        r.setMarketValue(marketValue == null ? null : new BigDecimal(marketValue));
        r.setUnrealizedPnl(unrealized == null ? null : new BigDecimal(unrealized));
        r.setRealizedPnlCum(new BigDecimal(realizedCum));
        r.setPriceAdjust("raw");
        return r;
    }

    @Test
    void equityCurveAggregatesByDate() {
        when(snapshotMapper.selectRange(D21, D22)).thenReturn(List.of(
                row(1, D21, "100", "10", "1100", "100", "0"),
                row(2, D21, "0", "0", "0", "0", "50"),
                row(1, D22, "100", "10", "1200", "200", "0"),
                row(2, D22, "0", "0", "0", "0", "50")));
        when(tradeMapper.netInvestedUntil(any())).thenReturn(new BigDecimal("1000"));

        EquityCurveResponse resp = service.equityCurve(D21, D22);

        assertThat(resp.points()).hasSize(2);
        EquityCurvePoint first = resp.points().get(0);
        assertThat(first.tradeDate()).isEqualTo(D21);
        assertThat(first.marketValue()).isEqualByComparingTo("1100");
        assertThat(first.costValue()).isEqualByComparingTo("1000");
        assertThat(first.unrealizedPnl()).isEqualByComparingTo("100");
        assertThat(first.realizedPnlCum()).isEqualByComparingTo("50");
        // 总盈亏 = 浮动 + 累计已实现：清仓标的的已实现不能丢
        assertThat(first.totalPnl()).isEqualByComparingTo("150");
        assertThat(first.positions()).isEqualTo(1);
        assertThat(first.pricedPositions()).isEqualTo(1);
        assertThat(first.complete()).isTrue();

        assertThat(resp.summary().firstDate()).isEqualTo(D21);
        assertThat(resp.summary().lastDate()).isEqualTo(D22);
        assertThat(resp.summary().marketValueChange()).isEqualByComparingTo("100");
        assertThat(resp.summary().endTotalPnl()).isEqualByComparingTo("250");
        assertThat(resp.summary().netInvested()).isEqualByComparingTo("1000");
        assertThat(resp.summary().returnPct()).isEqualByComparingTo("25.00");
        assertThat(resp.priceAdjust()).isEqualTo("raw");
        assertThat(resp.note()).contains("简单收益");
    }

    @Test
    void missingPriceMarksDayIncompleteInsteadOfCountingZero() {
        when(snapshotMapper.selectRange(D21, D21)).thenReturn(List.of(
                row(1, D21, "100", "10", null, null, "0")));

        EquityCurveResponse resp = service.equityCurve(D21, D21);

        EquityCurvePoint point = resp.points().get(0);
        assertThat(point.complete()).isFalse();
        assertThat(point.positions()).isEqualTo(1);
        assertThat(point.pricedPositions()).isZero();
        assertThat(point.marketValue()).isEqualByComparingTo("0");
        // 缺价当天不能给出收益率：分母是净投入，分子却只算了能取到价的部分
        assertThat(resp.summary().returnPct()).isNull();
    }

    @Test
    void emptySnapshotReturnsEmptyPointsWithNote() {
        when(snapshotMapper.selectRange(any(), any())).thenReturn(List.of());

        EquityCurveResponse resp = service.equityCurve(D20, D22);

        assertThat(resp.points()).isEmpty();
        assertThat(resp.summary().firstDate()).isNull();
        assertThat(resp.lastSnapshotDate()).isEqualTo(D22);
        assertThat(resp.note()).isNotBlank();
    }

    @Test
    void positionChangesUseBaselineBeforeRange() {
        Instrument instrument = new Instrument();
        instrument.setId(1L);
        instrument.setSymbol("600519");
        instrument.setName("贵州茅台");

        when(snapshotMapper.selectRange(D21, D22)).thenReturn(List.of(
                row(1, D21, "100", "10", "1100", "100", "0"),
                row(1, D22, "100", "10", "1200", "200", "0")));
        when(snapshotMapper.selectLatestBefore(D21)).thenReturn(List.of(
                row(1, D20, "50", "9", "450", "0", "0")));
        when(instrumentMapper.selectByIds(any())).thenReturn(List.of(instrument));
        when(tradeMapper.countInRange(eq(1L), any(), any())).thenReturn(1L);

        List<PositionChangeResponse> changes = service.positionChanges(D21, D22);

        assertThat(changes).hasSize(1);
        PositionChangeResponse change = changes.get(0);
        assertThat(change.symbol()).isEqualTo("600519");
        assertThat(change.openDate()).isEqualTo(D20);
        assertThat(change.closeDate()).isEqualTo(D22);
        assertThat(change.openQuantity()).isEqualByComparingTo("50");
        assertThat(change.closeQuantity()).isEqualByComparingTo("100");
        assertThat(change.quantityDelta()).isEqualByComparingTo("50");
        assertThat(change.avgCostDelta()).isEqualByComparingTo("1");
        assertThat(change.marketValueDelta()).isEqualByComparingTo("750");
        assertThat(change.unrealizedDelta()).isEqualByComparingTo("200");
        assertThat(change.realizedPnlInRange()).isEqualByComparingTo("0");
        assertThat(change.tradeCount()).isEqualTo(1);
        assertThat(change.closed()).isFalse();
    }

    @Test
    void positionChangesWithoutBaselineStartFromZeroAndFlagClosed() {
        when(snapshotMapper.selectRange(D21, D22)).thenReturn(List.of(
                row(1, D21, "100", "10", "1100", "100", "0"),
                row(1, D22, "0", "0", "0", "0", "120")));
        when(snapshotMapper.selectLatestBefore(D21)).thenReturn(List.of());
        when(instrumentMapper.selectByIds(any())).thenReturn(List.of());
        when(tradeMapper.countInRange(eq(1L), any(), any())).thenReturn(2L);

        List<PositionChangeResponse> changes = service.positionChanges(D21, D22);

        PositionChangeResponse change = changes.get(0);
        assertThat(change.openDate()).isNull();
        assertThat(change.openQuantity()).isEqualByComparingTo("0");
        assertThat(change.openAvgCost()).isNull();
        // 期初没有持仓：市值/浮盈确实是 0（不是"不知道"），只有"每股成本"没有定义
        assertThat(change.openMarketValue()).isEqualByComparingTo("0");
        assertThat(change.marketValueDelta()).isEqualByComparingTo("0");
        // 区间内开仓又清仓：期初/期末都是 0，所以数量变化为 0；
        // "中间发生过什么"由 closed + realizedPnlInRange 表达，不能靠 delta 猜
        assertThat(change.quantityDelta()).isEqualByComparingTo("0");
        assertThat(change.realizedPnlInRange()).isEqualByComparingTo("120");
        assertThat(change.closed()).isTrue();
        // 标的元数据缺失时不能编造代码
        assertThat(change.symbol()).isNull();
    }

    @Test
    void defaultRangeEndsAtLatestSnapshot() {
        when(snapshotMapper.selectRange(any(), any())).thenReturn(List.of());

        EquityCurveResponse resp = service.equityCurve(null, null);

        assertThat(resp.to()).isEqualTo(D22);
        assertThat(resp.from()).isEqualTo(D22.minusDays(180));
        Mockito.verify(snapshotMapper).selectRange(D22.minusDays(180), D22);
    }

    @Test
    void netInvestedUsesEndOfDay() {
        when(snapshotMapper.selectRange(any(), any())).thenReturn(List.of());
        ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);

        service.equityCurve(D21, D22);

        Mockito.verify(tradeMapper).netInvestedUntil(captor.capture());
        assertThat(captor.getValue().toLocalDate()).isEqualTo(D22);
        assertThat(captor.getValue().getHour()).isEqualTo(23);
    }
}
