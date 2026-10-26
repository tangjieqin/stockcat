package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Trade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0.7 持仓计算验收测试（docs/DATABASE.md §3.10）：
 * 多笔买入、部分卖出、清仓后再买入、超卖拒绝、费用计入成本。
 */
class HoldingComputationServiceTest {

    private final HoldingComputationService service = new HoldingComputationService();
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-20T09:35:00+08:00");

    private static Trade trade(String direction, String price, String qty, String fee, int minutesAfter) {
        Trade t = new Trade();
        t.setInstrumentId(1L);
        t.setDirection(direction);
        t.setPrice(new BigDecimal(price));
        t.setQuantity(new BigDecimal(qty));
        t.setFee(new BigDecimal(fee));
        t.setTradeTime(T0.plusMinutes(minutesAfter));
        return t;
    }

    @Test
    void multipleBuys_weightedAvgCost() {
        // 100@10 fee5 -> avg 10.05; 再 100@12 fee5 -> (1005+1205)/200 = 11.05
        List<Trade> trades = List.of(
                trade("BUY", "10", "100", "5", 0),
                trade("BUY", "12", "100", "5", 10));
        HoldingComputationService.HoldingState s = service.replay(trades);

        assertThat(s.quantity().compareTo(new BigDecimal("200"))).isEqualTo(0);
        assertThat(s.avgCost().compareTo(new BigDecimal("11.05"))).isEqualTo(0);
        assertThat(s.realizedPnl().compareTo(BigDecimal.ZERO)).isEqualTo(0);
    }

    @Test
    void feeIncludedInCost() {
        // 单笔买入：avg = (1000 + 5) / 100 = 10.05（费用计入成本）
        HoldingComputationService.HoldingState s =
                service.replay(List.of(trade("BUY", "10", "100", "5", 0)));
        assertThat(s.avgCost().compareTo(new BigDecimal("10.05"))).isEqualTo(0);
    }

    @Test
    void partialSell_realizedPnl() {
        // 承接 multipleBuys 场景（avg 11.05, 200 股），卖 50@13 fee2：
        // realized = (13 - 11.05)*50 - 2 = 95.5
        List<Trade> trades = new ArrayList<>(List.of(
                trade("BUY", "10", "100", "5", 0),
                trade("BUY", "12", "100", "5", 10),
                trade("SELL", "13", "50", "2", 20)));
        HoldingComputationService.HoldingState s = service.replay(trades);

        assertThat(s.quantity().compareTo(new BigDecimal("150"))).isEqualTo(0);
        assertThat(s.realizedPnl().compareTo(new BigDecimal("95.5"))).isEqualTo(0);
        // 摊薄成本不变
        assertThat(s.avgCost().compareTo(new BigDecimal("11.05"))).isEqualTo(0);
    }

    @Test
    void clearThenRebuy_freshCostBasis() {
        List<Trade> trades = List.of(
                trade("BUY", "10", "100", "5", 0),
                trade("SELL", "12", "100", "3", 10),
                trade("BUY", "20", "100", "0", 20));
        HoldingComputationService.HoldingState s = service.replay(trades);

        // 清仓后成本清零，再买入重新起算：avg = 20
        assertThat(s.quantity().compareTo(new BigDecimal("100"))).isEqualTo(0);
        assertThat(s.avgCost().compareTo(new BigDecimal("20"))).isEqualTo(0);
        // realized = (12 - 10.05)*100 - 3 = 192
        assertThat(s.realizedPnl().compareTo(new BigDecimal("192"))).isEqualTo(0);
    }

    @Test
    void oversellRejected_withAvailableQuantity() {
        List<Trade> trades = List.of(trade("BUY", "10", "100", "0", 0));
        List<Trade> oversell = new ArrayList<>(trades);
        oversell.add(trade("SELL", "11", "150", "0", 10));

        assertThatThrownBy(() -> service.replay(oversell))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("当前可卖 100")
                .hasMessageContaining("150");
    }

    @Test
    void replaySorted_handlesOutOfOrderInput() {
        // 传入乱序（后买的在前），replaySorted 应按时间排序
        List<Trade> trades = new ArrayList<>(List.of(
                trade("BUY", "12", "100", "5", 10),
                trade("BUY", "10", "100", "5", 0)));
        HoldingComputationService.HoldingState s = service.replaySorted(trades);
        assertThat(s.avgCost().compareTo(new BigDecimal("11.05"))).isEqualTo(0);
    }
}
