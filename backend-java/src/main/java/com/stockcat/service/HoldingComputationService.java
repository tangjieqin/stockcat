package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Trade;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 持仓重放计算（docs/DATABASE.md §3.10，不建持仓表，确定性重放 trade_record）。
 *
 * - 买入：数量累加；摊薄成本 = (原数量×原摊薄成本 + 买入数量×价格 + 费用) / 新数量（费用计入成本）
 * - 卖出：数量不得超过当时持有量（超卖抛 CONFLICT 409，message 带当前可卖数量）；
 *         已实现盈亏 += (卖价 − 摊薄成本)×数量 − 费用
 * - 清仓后成本清零，再买入重新起算
 * - 全程 BigDecimal，禁止浮点
 */
@Service
public class HoldingComputationService {

    private static final int COST_SCALE = 6;

    /** 重放后的持仓状态 */
    public record HoldingState(BigDecimal quantity, BigDecimal avgCost, BigDecimal realizedPnl) {
        public static final HoldingState EMPTY =
                new HoldingState(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * 按传入顺序重放流水（调用方保证按 trade_time 升序）。任一笔卖出超卖即抛 409。
     */
    public HoldingState replay(List<Trade> trades) {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal avgCost = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;

        for (Trade t : trades) {
            BigDecimal q = t.getQuantity();
            if ("BUY".equals(t.getDirection())) {
                BigDecimal newQty = qty.add(q);
                if (newQty.signum() > 0) {
                    BigDecimal totalCost = qty.multiply(avgCost).add(q.multiply(t.getPrice())).add(t.getFee());
                    avgCost = totalCost.divide(newQty, COST_SCALE, RoundingMode.HALF_UP);
                }
                qty = newQty;
            } else if ("SELL".equals(t.getDirection())) {
                if (q.compareTo(qty) > 0) {
                    throw ApiException.conflict(
                            "卖出数量超过当时持有量：当前可卖 " + qty.stripTrailingZeros().toPlainString()
                                    + "，尝试卖出 " + q.stripTrailingZeros().toPlainString());
                }
                BigDecimal pnl = t.getPrice().subtract(avgCost).multiply(q).subtract(t.getFee());
                realized = realized.add(pnl);
                qty = qty.subtract(q);
                if (qty.signum() == 0) {
                    avgCost = BigDecimal.ZERO; // 清仓后成本清零
                }
            } else {
                throw ApiException.validationError("未知交易方向: " + t.getDirection());
            }
        }
        return new HoldingState(qty, avgCost, realized);
    }

    /**
     * 将流水按 (trade_time, id) 排序后重放。
     */
    public HoldingState replaySorted(List<Trade> trades) {
        trades.sort(Comparator.comparing(Trade::getTradeTime).thenComparing(Trade::getId));
        return replay(trades);
    }

    /**
     * 计算某时点前（不含新交易）的持有数量，用于卖出校验提示。
     */
    public BigDecimal availableBefore(List<Trade> sortedTrades, OffsetDateTime beforeTime) {
        BigDecimal qty = BigDecimal.ZERO;
        for (Trade t : sortedTrades) {
            if (!t.getTradeTime().isBefore(beforeTime)) {
                break;
            }
            if ("BUY".equals(t.getDirection())) {
                qty = qty.add(t.getQuantity());
            } else if ("SELL".equals(t.getDirection())) {
                qty = qty.subtract(t.getQuantity());
            }
        }
        return qty.max(BigDecimal.ZERO);
    }
}
