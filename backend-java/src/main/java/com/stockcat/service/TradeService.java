package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Instrument;
import com.stockcat.domain.Trade;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.mapper.TradeMapper;
import com.stockcat.web.dto.CreateTradeRequest;
import com.stockcat.web.dto.ImportTradeRequest;
import com.stockcat.web.dto.ImportTradesResponse;
import com.stockcat.web.dto.PageResponse;
import com.stockcat.web.dto.TradeResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易流水（docs/API.md §6）：超卖校验（409 带可卖数量）、删除后重算受影响持仓。
 */
@Service
public class TradeService {

    private final TradeMapper tradeMapper;
    private final InstrumentMapper instrumentMapper;
    private final HoldingComputationService computation;
    private final InstrumentService instrumentService;

    public TradeService(TradeMapper tradeMapper, InstrumentMapper instrumentMapper,
                        HoldingComputationService computation, InstrumentService instrumentService) {
        this.tradeMapper = tradeMapper;
        this.instrumentMapper = instrumentMapper;
        this.computation = computation;
        this.instrumentService = instrumentService;
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
     * 交割单批量导入：标的按 market+symbol 查找/自动创建，逐行返回结果，失败不影响其他行。
     * 行按 tradeTime 升序落库——卖出行的超卖校验依赖之前的买入已插入。
     * 行内校验错误（缺字段/超卖）逐行捕获；行与行之间不共用事务，避免一行失败拖垮整批。
     */
    public ImportTradesResponse importTrades(List<ImportTradeRequest> rows) {
        if (rows == null || rows.isEmpty()) {
            throw ApiException.validationError("导入内容为空");
        }
        if (rows.size() > 500) {
            throw ApiException.validationError("单次最多导入 500 行，请分批粘贴");
        }

        // 同一标的只做一次查找/创建（避免重复调行情源验证名称）
        Map<String, Instrument> instrumentCache = new LinkedHashMap<>();
        List<ImportTradesResponse.ItemResult> results = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;

        // 原始下标 -> 排序后下标：按成交时间升序处理，结果仍按原始行序返回
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> {
            OffsetDateTime ta = rows.get(a).tradeTime();
            OffsetDateTime tb = rows.get(b).tradeTime();
            if (ta == null || tb == null) {
                return 0;
            }
            return ta.compareTo(tb);
        });

        for (int idx : order) {
            ImportTradeRequest row = rows.get(idx);
            try {
                Instrument instrument = resolveInstrument(row, instrumentCache);
                CreateTradeRequest req = new CreateTradeRequest(
                        instrument.getId(), row.direction(), row.price(), row.quantity(),
                        row.fee(), row.tradeTime(), row.note());
                TradeResponse created = create(req);
                succeeded++;
                results.add(ImportTradesResponse.ItemResult.ok(idx, created.id()));
            } catch (ApiException e) {
                failed++;
                results.add(ImportTradesResponse.ItemResult.fail(idx, e.getErrorCode().code(), e.getMessage()));
            }
        }
        results.sort((a, b) -> Integer.compare(a.index(), b.index()));
        return new ImportTradesResponse(rows.size(), succeeded, failed, results);
    }

    /** 按 market+symbol 查找标的，不存在时自动创建（复用自选/监控的验证逻辑）。 */
    private Instrument resolveInstrument(ImportTradeRequest row, Map<String, Instrument> cache) {
        if (row.symbol() == null || row.symbol().isBlank()) {
            throw ApiException.validationError("缺少 6 位标的代码");
        }
        String market = row.market() == null || row.market().isBlank() ? "CN_A" : row.market();
        String type = row.type() == null || row.type().isBlank() ? "STOCK" : row.type();
        String key = market + "|" + row.symbol().trim() + "|" + type;
        Instrument cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Instrument instrument = instrumentService.getOrCreateVerified(market, row.symbol().trim(), type, row.name());
        cache.put(key, instrument);
        return instrument;
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
