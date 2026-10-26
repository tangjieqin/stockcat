package com.stockcat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.alert.RuleEvaluator;
import com.stockcat.alert.RuleEvaluatorRegistry;
import com.stockcat.common.ApiException;
import com.stockcat.domain.AlertEvent;
import com.stockcat.domain.AlertRule;
import com.stockcat.web.dto.QuoteResponse;
import com.stockcat.mapper.AlertEventMapper;
import com.stockcat.mapper.AlertRuleMapper;
import com.stockcat.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 规则评估与事件生成（P0.5 核心）。
 * - 仅 VALID 行情可触发（STALE/PARTIAL/INVALID/缺失一律跳过，不伪造事件）
 * - 冷却期内跳过（last_triggered_at + cooldown）
 * - 去重键 = rule-{id}-{触发分钟}，配合唯一约束 ON CONFLICT DO NOTHING
 * - 事件携带规则快照字段，规则删除/修改后历史仍可解释
 */
@Service
public class AlertEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationService.class);

    private final RuleEvaluatorRegistry registry;
    private final AlertRuleMapper ruleMapper;
    private final AlertEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    public AlertEvaluationService(RuleEvaluatorRegistry registry, AlertRuleMapper ruleMapper,
                                  AlertEventMapper eventMapper, ObjectMapper objectMapper,
                                  NotificationService notificationService) {
        this.registry = registry;
        this.ruleMapper = ruleMapper;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    /**
     * 评估单条规则。返回生成的告警事件（未触发/跳过返回 empty）。
     */
    public Optional<AlertEvent> evaluate(AlertRule rule, QuoteResponse quote, OffsetDateTime now) {
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
            return Optional.empty();
        }
        // 1. 行情有效性：STALE/PARTIAL/INVALID/缺失不触发
        if (quote == null || !"VALID".equals(quote.dataStatus()) || quote.lastPrice() == null) {
            return Optional.empty();
        }
        // 2. 冷却检查
        int cooldown = rule.getCooldownMinutes() == null ? 30 : rule.getCooldownMinutes();
        if (rule.getLastTriggeredAt() != null
                && rule.getLastTriggeredAt().plusMinutes(cooldown).isAfter(now)) {
            return Optional.empty();
        }
        // 3. 评估
        RuleEvaluator evaluator = registry.get(rule.getRuleType());
        JsonNode params = parse(rule.getParamsJson());
        if (!evaluator.evaluate(params, quote)) {
            return Optional.empty();
        }
        // 4. 生成事件（快照字段 + 去重键）
        OffsetDateTime triggeredAt = now.truncatedTo(ChronoUnit.SECONDS);
        AlertEvent event = new AlertEvent();
        event.setRuleId(rule.getId());
        event.setInstrumentId(rule.getInstrumentId());
        event.setRuleNameSnapshot(rule.getName());
        event.setRuleTypeSnapshot(rule.getRuleType());
        event.setConditionParamsSnapshot(rule.getParamsJson());
        event.setObservedPrice(quote.lastPrice());
        event.setObservedChangePercent(quote.changePercent());
        event.setObservedDetail(null); // P0 四种类型观测值由 price/changePercent 表达；P1 扩展类型再填
        event.setQuoteMarketTime(quote.marketTime());
        event.setTriggeredAt(triggeredAt);
        event.setDeduplicationKey("rule-" + rule.getId() + "-" + triggeredAt.truncatedTo(ChronoUnit.MINUTES)
                .toInstant().toString());

        int inserted = eventMapper.insert(event);
        if (inserted == 0) {
            log.debug("事件被去重抑制: rule={} key={}", rule.getId(), event.getDeduplicationKey());
            return Optional.empty();
        }
        ruleMapper.updateLastTriggered(rule.getId(), triggeredAt);
        log.info("告警触发: rule={}({}) {} observed={}", rule.getName(), rule.getRuleType(),
                rule.getSymbol(), quote.lastPrice());
        try {
            var notification = notificationService.dispatch(event);
            log.info("通知状态: {} ({})", notification.getStatus(), notification.getId());
        } catch (Exception e) {
            log.error("通知派发异常(不影响事件): event={} err={}", event.getId(), e.getMessage());
        }
        return Optional.of(event);
    }

    private JsonNode parse(String paramsJson) {
        try {
            return objectMapper.readTree(paramsJson == null ? "{}" : paramsJson);
        } catch (Exception e) {
            throw ApiException.validationError("规则参数不是合法 JSON");
        }
    }
}
