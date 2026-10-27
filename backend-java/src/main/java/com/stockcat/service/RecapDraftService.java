package com.stockcat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.assistant.AssistantClient;
import com.stockcat.common.ApiException;
import com.stockcat.domain.MarketRecap;
import com.stockcat.mapper.MarketRecapMapper;
import com.stockcat.web.dto.RecapDtos.SaveRecapDraftRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * AI 复盘草稿的落库与确认闭环（P2.4）。
 *
 * <p>状态机只有一条合法路径：{@code AI_DRAFT → MANUAL}，由用户在复盘页编辑后保存触发
 * （{@link RecapService#update} 里实现）。
 *
 * <p>三条硬约束：
 * <ul>
 *   <li><b>AI 不覆盖人工内容</b>：同一天已有 MANUAL 时生成与保存都返回 409，不是"悄悄跳过"；</li>
 *   <li><b>重新生成只覆盖草稿</b>：已存在 AI_DRAFT 时原地刷新，状态仍为 AI_DRAFT；</li>
 *   <li><b>溯源信息跟着草稿走</b>：生成时间、模型名、数据截至、限制清单与正文同一条记录落库，
 *       确认转 MANUAL 后仍保留——几周后回看才知道"当时数据截至哪儿"。</li>
 * </ul>
 */
@Service
public class RecapDraftService {

    static final int MAX_TITLE_CHARS = 128; // market_recap.title VARCHAR(128)

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final MarketRecapMapper mapper;
    private final AssistantClient client;
    private final ObjectMapper objectMapper;

    public RecapDraftService(MarketRecapMapper mapper, AssistantClient client, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成草稿（**不落库**）。返回 Python 的原始响应体：draft / limitations / dataAsOf / toolCalls。
     *
     * <p>先查当天是否已有 MANUAL：有就不必花 token 生成一份注定不能保存的草稿。
     */
    public Map<String, Object> generate(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now(CN) : date;
        MarketRecap existing = mapper.findByDate(target);
        if (existing != null && "MANUAL".equals(existing.getStatus())) {
            throw alreadyManual(target, existing);
        }
        return client.recapDraft(target.toString());
    }

    /**
     * 保存草稿。同一天已有 MANUAL → 409；已有 AI_DRAFT → 覆盖；都没有 → 新建。
     *
     * <p>正文以请求体为准（用户可能已先改过），溯源信息同样来自请求体——
     * 它们由生成接口原样回传，不是前端算出来的。
     */
    @Transactional
    public MarketRecap saveDraft(SaveRecapDraftRequest req) {
        String title = req.title() == null ? "" : req.title().trim();
        String content = req.content() == null ? "" : req.content().trim();
        if (title.isEmpty() || content.isEmpty()) {
            throw ApiException.validationError("title、content 为必填");
        }
        if (title.length() > MAX_TITLE_CHARS) {
            throw ApiException.validationError("标题不能超过 " + MAX_TITLE_CHARS + " 字，当前 " + title.length() + " 字");
        }

        LocalDate date = req.recapDate();
        MarketRecap existing = mapper.findByDate(date);
        if (existing != null && "MANUAL".equals(existing.getStatus())) {
            throw alreadyManual(date, existing);
        }

        String limitations = toJson(req.limitations());
        OffsetDateTime generatedAt = req.generatedAt() == null
                ? OffsetDateTime.now(ZoneOffset.UTC) : req.generatedAt();

        if (existing != null) {
            int updated = mapper.updateDraft(existing.getId(), title, req.marketSummary(),
                    req.sectorNotes(), content, generatedAt, req.model(), req.dataAsOf(), limitations);
            if (updated == 0) {
                // 读取与写入之间被用户确认成 MANUAL：宁可报错也不覆盖
                throw ApiException.conflict("这篇复盘刚刚已转为正式复盘（MANUAL），AI 草稿不再覆盖");
            }
            return mapper.findById(existing.getId());
        }

        MarketRecap recap = new MarketRecap();
        recap.setRecapDate(date);
        recap.setTitle(title);
        recap.setMarketSummary(req.marketSummary());
        recap.setSectorNotes(req.sectorNotes());
        recap.setContent(content);
        recap.setStatus("AI_DRAFT");
        recap.setAiGeneratedAt(generatedAt);
        recap.setAiModel(req.model());
        recap.setAiDataAsOf(req.dataAsOf());
        recap.setAiLimitations(limitations);
        mapper.insert(recap);
        return mapper.findById(recap.getId());
    }

    private ApiException alreadyManual(LocalDate date, MarketRecap existing) {
        return ApiException.conflict(
                date + " 已有一篇正式复盘《" + existing.getTitle() + "》，AI 草稿不会覆盖人工内容；"
                        + "如需重写请直接编辑该复盘");
    }

    /** limitations 存 JSONB：null 表示"没有记录"，不要伪造成空数组。 */
    private String toJson(List<String> items) {
        if (items == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw ApiException.validationError("limitations 序列化失败");
        }
    }
}
