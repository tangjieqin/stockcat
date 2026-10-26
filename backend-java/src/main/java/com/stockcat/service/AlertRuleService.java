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
                            String paramsJson, Integer cooldownSeconds, Boolean enabled) {
        Instrument instrument = instrumentMapper.findById(instrumentId);
        if (instrument == null) {
            throw ApiException.notFound("标的不存在: id=" + instrumentId);
        }
        // 规则类型与参数校验（含边界语义说明见评估器实现）
        RuleEvaluator evaluator = registry.get(ruleType);
        evaluator.validateParams(parse(paramsJson));
        // 溢价类规则只对 ETF/LOF 有意义：挂到股票上会变成永远不触发的死规则
        if (evaluator.requiresPremium() && !FundReferenceService.isFundType(instrument.getInstrumentType())) {
            throw ApiException.validationError(
                    "溢价类规则仅适用于 ETF/LOF，该标的是 " + instrument.getInstrumentType());
        }
        if (cooldownSeconds != null && cooldownSeconds < 0) {
            throw ApiException.validationError("cooldownSeconds 不能为负");
        }

        AlertRule rule = new AlertRule();
        rule.setName(name);
        rule.setInstrumentId(instrumentId);
        rule.setRuleType(ruleType);
        rule.setParamsJson(paramsJson);
        rule.setCooldownSeconds(cooldownSeconds == null ? 1800 : cooldownSeconds);
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
                                   String paramsJson, Integer cooldownSeconds, Boolean enabled) {
        List<BatchItem> results = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        for (Long instrumentId : instrumentIds) {
            try {
                AlertRule created = create(name, instrumentId, ruleType, paramsJson, cooldownSeconds, enabled);
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
     * PATCH：允许 name/ruleType/params/cooldownSeconds/enabled（标的不允许换，需删除重建）。
     * ruleType 可改：以目标类型校验参数（未传 params 时用现有参数校验，不兼容则报错）。
     */
    @Transactional
    public AlertRule update(Long id, String name, String ruleType, String paramsJson,
                            Integer cooldownSeconds, Boolean enabled) {
        AlertRule existing = ruleMapper.findById(id);
        if (existing == null) {
            throw ApiException.notFound("规则不存在: id=" + id);
        }
        String targetType = ruleType != null ? ruleType : existing.getRuleType();
        RuleEvaluator evaluator = registry.get(targetType); // 未知类型在 registry.get 内报 VALIDATION_ERROR
        String effectiveParams = paramsJson != null ? paramsJson : existing.getParamsJson();
        evaluator.validateParams(parse(effectiveParams));
        // 标的不能换，所以这里只需再挡一次"把股票规则改成溢价类型"
        if (ruleType != null && evaluator.requiresPremium()) {
            Instrument instrument = instrumentMapper.findById(existing.getInstrumentId());
            if (instrument != null && !FundReferenceService.isFundType(instrument.getInstrumentType())) {
                throw ApiException.validationError(
                        "溢价类规则仅适用于 ETF/LOF，该标的是 " + instrument.getInstrumentType());
            }
        }
        ruleMapper.updateFields(id, name, ruleType, paramsJson, cooldownSeconds, enabled);
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
