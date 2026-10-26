package com.stockcat.service;

import com.stockcat.domain.Trade;
import com.stockcat.mapper.TradeMapper;
import com.stockcat.web.dto.PositionResponse;
import com.stockcat.web.dto.QuoteResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 持仓查询：由 trade_record 重放得出（不建持仓表）。
 * 市值/浮动盈亏仅基于最新有效快照；缺失或过期时为 null + priceStatus，不按 0 计算（docs/API.md §6）。
 */
@Service
public class PositionService {

    private final TradeMapper tradeMapper;
    private final HoldingComputationService computation;
    private final QuoteService quoteService;

    public PositionService(TradeMapper tradeMapper, HoldingComputationService computation, QuoteService quoteService) {
        this.tradeMapper = tradeMapper;
        this.computation = computation;
        this.quoteService = quoteService;
    }

    public List<PositionResponse> list() {
        List<Long> instrumentIds = tradeMapper.selectDistinctInstrumentIds();
        if (instrumentIds.isEmpty()) {
            return List.of();
        }
        Map<Long, QuoteResponse> quotes = quoteService.latest(instrumentIds);

        List<PositionResponse> result = new ArrayList<>();
        for (Long instrumentId : instrumentIds) {
            List<Trade> trades = tradeMapper.selectByInstrumentOrderByTime(instrumentId);
            if (trades.isEmpty()) {
                continue;
            }
            Trade first = trades.get(0);
            HoldingComputationService.HoldingState state = computation.replay(trades);
            if (state.quantity().signum() <= 0) {
                continue; // 已清仓的标的不出现在当前持仓列表
            }

            QuoteResponse q = quotes.get(instrumentId);
            boolean validPrice = q != null && "VALID".equals(q.dataStatus()) && q.lastPrice() != null;
            BigDecimal lastPrice = validPrice ? BigDecimal.valueOf(q.lastPrice()) : null;
            BigDecimal marketValue = validPrice ? lastPrice.multiply(state.quantity()) : null;
            BigDecimal unrealized = validPrice
                    ? marketValue.subtract(state.quantity().multiply(state.avgCost()))
                    : null;

            result.add(new PositionResponse(
                    instrumentId, first.getSymbol(), first.getInstrumentName(),
                    state.quantity(), state.avgCost(), lastPrice, marketValue, unrealized,
                    state.realizedPnl(),
                    q != null ? q.collectedAt() : null,
                    q != null ? q.dataStatus() : "NO_DATA"));
        }
        return result;
    }
}
