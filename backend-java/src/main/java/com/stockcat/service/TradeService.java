package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Instrument;
import com.stockcat.domain.Trade;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.mapper.TradeMapper;
import com.stockcat.web.dto.CreateTradeRequest;
import com.stockcat.web.dto.PageResponse;
import com.stockcat.web.dto.TradeResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 交易流水（docs/API.md §6）：超卖校验（409 带可卖数量）、删除后重算受影响持仓。
 */
@Service
public class TradeService {

    private final TradeMapper tradeMapper;
    private final InstrumentMapper instrumentMapper;
    private final HoldingComputationService computation;

    public TradeService(TradeMapper tradeMapper, InstrumentMapper instrumentMapper,
                        HoldingComputationService computation) {
        this.tradeMapper = tradeMapper;
        this.instrumentMapper = instrumentMapper;
        this.computation = computation;
    }

    public PageResponse<TradeResponse> list(Long instrumentId, String direction,
                                            OffsetDateTime from, OffsetDateTime to, int page, int size) {
        List<Trade> rows = tradeMapper.selectPage(instrumentId, direction, from, to, page * size, size);
        long total = tradeMapper.countPage(instrumentId, direction, from, to);
        return new PageResponse<>(rows.stream().map(TradeResponse::from).toList(), page, size, total);
    }

    @Transactional
    public TradeResponse create(CreateTradeRequest req) {
        Instrument instrument = instrumentMapper.findById(req.instrumentId());
        if (instrument == null) {
            throw ApiException.notFound("标的不存在: id=" + req.instrumentId());
        }
        if (!"BUY".equals(req.direction()) && !"SELL".equals(req.direction())) {
            throw ApiException.validationError("direction 仅支持 BUY/SELL");
        }
        if (req.price().signum() <= 0) {
            throw ApiException.validationError("price 必须 > 0");
        }
        if (req.quantity().signum() <= 0) {
            throw ApiException.validationError("quantity 必须 > 0");
        }
        BigDecimal fee = req.fee() == null ? BigDecimal.ZERO : req.fee();
        if (fee.signum() < 0) {
            throw ApiException.validationError("fee 不能为负");
        }

        if ("SELL".equals(req.direction())) {
            // 校验该时点前的持有量（docs/DATABASE.md §3.9）
            List<Trade> existing = tradeMapper.selectByInstrumentOrderByTime(req.instrumentId());
            BigDecimal available = computation.availableBefore(existing, req.tradeTime());
            if (req.quantity().compareTo(available) > 0) {
                throw ApiException.conflict("卖出数量超过当时持有量：当前可卖 "
                        + available.stripTrailingZeros().toPlainString() + "，尝试卖出 "
                        + req.quantity().stripTrailingZeros().toPlainString());
            }
        }

        Trade trade = new Trade();
        trade.setInstrumentId(req.instrumentId());
        trade.setDirection(req.direction());
        trade.setPrice(req.price());
        trade.setQuantity(req.quantity());
        trade.setFee(fee);
        trade.setTradeTime(req.tradeTime());
        trade.setNote(req.note());
        tradeMapper.insert(trade);
        return TradeResponse.from(tradeMapper.findById(trade.getId()));
    }

    /**
     * 删除后重算受影响标的持仓；若重放出现超卖（删除的买入支撑了后续卖出）返回 409 拒绝。
     */
    @Transactional
    public void delete(Long id) {
        Trade existing = tradeMapper.findById(id);
        if (existing == null) {
            throw ApiException.notFound("交易流水不存在: id=" + id);
        }
        List<Trade> remaining = new java.util.ArrayList<>(
                tradeMapper.selectByInstrumentOrderByTime(existing.getInstrumentId()));
        remaining.removeIf(t -> t.getId().equals(id));
        // 重放校验（可能抛 409）
        computation.replaySorted(remaining);
        tradeMapper.deleteById(id);
    }
}
