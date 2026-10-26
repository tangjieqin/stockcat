package com.stockcat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.alert.RuleEvaluator;
import com.stockcat.alert.RuleEvaluatorRegistry;
import com.stockcat.common.ApiException;
import com.stockcat.domain.AlertRule;
import com.stockcat.domain.Instrument;
import com.stockcat.mapper.AlertRuleMapper;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.web.dto.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 告警规则 CRUD（docs/API.md §4）。
 * 创建时校验：规则类型已支持 + 参数 schema + 标的存在，避免死规则。
 */
@Service
public class AlertRuleService {

    private final AlertRuleMapper ruleMapper;
    private final InstrumentMapper instrumentMapper;
    private final RuleEvaluatorRegistry registry;
    private final ObjectMapper objectMapper;

    public AlertRuleService(AlertRuleMapper ruleMapper, InstrumentMapper instrumentMapper,
                            RuleEvaluatorRegistry registry, ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.instrumentMapper = instrumentMapper;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    public PageResponse<AlertRule> list(Long instrumentId, Boolean enabled, int page, int size) {
        List<AlertRule> rows = ruleMapper.selectPage(instrumentId, enabled, page * size, size);
        long total = ruleMapper.countPage(instrumentId, enabled);
        return new PageResponse<>(rows, page, size, total);
    }

    @Transactional
    public AlertRule create(String name, Long instrumentId, String ruleType,
                            String paramsJson, Integer cooldownMinutes, Boolean enabled) {
        Instrument instrument = instrumentMapper.findById(instrumentId);
        if (instrument == null) {
            throw ApiException.notFound("标的不存在: id=" + instrumentId);
        }
        // 规则类型与参数校验（含边界语义说明见评估器实现）
        RuleEvaluator evaluator = registry.get(ruleType);
        evaluator.validateParams(parse(paramsJson));
        if (cooldownMinutes != null && cooldownMinutes < 0) {
            throw ApiException.validationError("cooldownMinutes 不能为负");
        }

        AlertRule rule = new AlertRule();
        rule.setName(name);
        rule.setInstrumentId(instrumentId);
        rule.setRuleType(ruleType);
        rule.setParamsJson(paramsJson);
        rule.setCooldownMinutes(cooldownMinutes == null ? 30 : cooldownMinutes);
        rule.setEnabled(enabled == null || enabled);
        ruleMapper.insert(rule);
        return ruleMapper.findById(rule.getId());
    }

    /**
     * 批量创建：为多个标的创建相同类型与参数的规则，部分失败不影响其他（docs/API.md §4）。
     */
    @Transactional
    public record BatchItem(Long instrumentId, String status, Long ruleId, String message) {
    }

    public record BatchResult(int requested, int succeeded, int failed, List<BatchItem> results) {
    }

    public BatchResult createBatch(List<Long> instrumentIds, String name, String ruleType,
                                   String paramsJson, Integer cooldownMinutes, Boolean enabled) {
        List<BatchItem> results = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        for (Long instrumentId : instrumentIds) {
            try {
                AlertRule created = create(name, instrumentId, ruleType, paramsJson, cooldownMinutes, enabled);
                results.add(new BatchItem(instrumentId, "SUCCESS", created.getId(), null));
                succeeded++;
            } catch (ApiException e) {
                results.add(new BatchItem(instrumentId, "FAILED", null, e.getMessage()));
                failed++;
            }
        }
        return new BatchResult(instrumentIds.size(), succeeded, failed, results);
    }

    /**
     * PATCH：只允许 name/params/cooldownMinutes/enabled（不允许换标的或 ruleType）。
     */
    @Transactional
    public AlertRule update(Long id, String name, String paramsJson, Integer cooldownMinutes, Boolean enabled) {
        AlertRule existing = ruleMapper.findById(id);
        if (existing == null) {
            throw ApiException.notFound("规则不存在: id=" + id);
        }
        if (paramsJson != null) {
            RuleEvaluator evaluator = registry.get(existing.getRuleType());
            evaluator.validateParams(parse(paramsJson));
        }
        ruleMapper.updateFields(id, name, paramsJson, cooldownMinutes, enabled);
        return ruleMapper.findById(id);
    }

    public void delete(Long id) {
        int deleted = ruleMapper.deleteById(id);
        if (deleted == 0) {
            throw ApiException.notFound("规则不存在: id=" + id);
        }
        // 历史事件与通知保留（快照字段保证可解释），不级联删除
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String paramsJson) {
        try {
            return objectMapper.readTree(paramsJson == null ? "{}" : paramsJson);
        } catch (Exception e) {
            throw ApiException.validationError("params 不是合法 JSON");
        }
    }
}
