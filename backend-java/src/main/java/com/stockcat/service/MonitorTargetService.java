package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Instrument;
import com.stockcat.mapper.MonitorTargetMapper;
import com.stockcat.web.dto.CreateMonitorTargetRequest;
import com.stockcat.web.dto.InstrumentResponse;
import com.stockcat.web.dto.MonitorTargetResponse;
import com.stockcat.web.dto.QuoteResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 监控标的（monitor_target）：告警引擎的监控对象集合，与自选列表相互独立。
 * 对应 docs/API.md §2。
 */
@Service
public class MonitorTargetService {

    private final MonitorTargetMapper monitorTargetMapper;
    private final InstrumentService instrumentService;
    private final QuoteService quoteService;

    public MonitorTargetService(MonitorTargetMapper monitorTargetMapper,
                                InstrumentService instrumentService,
                                QuoteService quoteService) {
        this.monitorTargetMapper = monitorTargetMapper;
        this.instrumentService = instrumentService;
        this.quoteService = quoteService;
    }

    public List<MonitorTargetResponse> list() {
        List<MonitorTargetResponse> items = monitorTargetMapper.selectAll().stream()
                .map(MonitorTargetResponse::from)
                .toList();
        if (items.isEmpty()) {
            return items;
        }
        Map<Long, QuoteResponse> quotes = quoteService.latest(items.stream().map(MonitorTargetResponse::id).toList());
        return items.stream()
                .map(item -> new MonitorTargetResponse(item.id(), item.instrument(), item.enabled(),
                        item.createdAt(), quotes.get(item.id())))
                .toList();
    }

    /**
     * 添加监控标的：instrument 已存在则复用，不存在则通过行情源验证并自动补全名称。
     */
    @Transactional
    public MonitorTargetResponse add(CreateMonitorTargetRequest req) {
        Instrument instrument = instrumentService.getOrCreateVerified(req.market(), req.symbol(), req.type(), req.name());
        if (monitorTargetMapper.existsByInstrumentId(instrument.getId()) > 0) {
            throw ApiException.conflict("该标的已在监控列表中: " + instrument.getSymbol() + " " + instrument.getName());
        }
        monitorTargetMapper.insert(instrument.getId());
        Long targetId = monitorTargetMapper.findIdByInstrumentId(instrument.getId());
        return MonitorTargetResponse.from(monitorTargetMapper.findById(targetId));
    }

    public MonitorTargetResponse updateEnabled(Long id, boolean enabled) {
        if (monitorTargetMapper.findById(id) == null) {
            throw ApiException.notFound("监控标的不存在: id=" + id);
        }
        monitorTargetMapper.updateEnabled(id, enabled);
        return MonitorTargetResponse.from(monitorTargetMapper.findById(id));
    }

    public void delete(Long id) {
        int deleted = monitorTargetMapper.deleteById(id);
        if (deleted == 0) {
            throw ApiException.notFound("监控标的不存在: id=" + id);
        }
    }
}
