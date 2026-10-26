package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.MarketRecap;
import com.stockcat.mapper.MarketRecapMapper;
import com.stockcat.web.dto.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 市场复盘（docs/API.md §7）：一天一篇（recap_date 唯一）；用户手工创建恒为 MANUAL，
 * P2 的 AI 只能生成 AI_DRAFT 且编辑保存后转为 MANUAL（用户确认），不得覆盖手工内容。
 */
@Service
public class RecapService {

    private final MarketRecapMapper mapper;

    public RecapService(MarketRecapMapper mapper) {
        this.mapper = mapper;
    }

    public PageResponse<MarketRecap> list(LocalDate date, LocalDate from, LocalDate to,
                                          String keyword, int page, int size) {
        List<MarketRecap> rows = mapper.selectPage(date, from, to, keyword, page * size, size);
        long total = mapper.countPage(date, from, to, keyword);
        return new PageResponse<>(rows, page, size, total);
    }

    public MarketRecap get(Long id) {
        MarketRecap recap = mapper.findById(id);
        if (recap == null) {
            throw ApiException.notFound("复盘不存在: id=" + id);
        }
        return recap;
    }

    @Transactional
    public MarketRecap create(LocalDate recapDate, String title, String marketSummary,
                              String sectorNotes, String content, String relatedAlertEventIds) {
        if (recapDate == null || title == null || title.isBlank() || content == null || content.isBlank()) {
            throw ApiException.validationError("recapDate、title、content 为必填");
        }
        if (mapper.existsByDate(recapDate) > 0) {
            throw ApiException.conflict("该交易日已有复盘: " + recapDate + "（一天一篇）");
        }
        MarketRecap recap = new MarketRecap();
        recap.setRecapDate(recapDate);
        recap.setTitle(title.trim());
        recap.setMarketSummary(marketSummary);
        recap.setSectorNotes(sectorNotes);
        recap.setContent(content);
        recap.setRelatedAlertEventIds(relatedAlertEventIds);
        recap.setStatus("MANUAL"); // 手工创建恒为 MANUAL
        mapper.insert(recap);
        return mapper.findById(recap.getId());
    }

    @Transactional
    public MarketRecap update(Long id, String title, String marketSummary, String sectorNotes,
                              String content, String relatedAlertEventIds) {
        MarketRecap existing = get(id);
        mapper.updateFields(id, title, marketSummary, sectorNotes, content, relatedAlertEventIds, null);
        // AI_DRAFT 经用户编辑保存后转为 MANUAL（用户确认语义）
        if ("AI_DRAFT".equals(existing.getStatus())) {
            mapper.updateFields(id, null, null, null, null, null, "MANUAL");
        }
        return get(id);
    }

    public void delete(Long id) {
        int deleted = mapper.deleteById(id);
        if (deleted == 0) {
            throw ApiException.notFound("复盘不存在: id=" + id);
        }
    }
}
