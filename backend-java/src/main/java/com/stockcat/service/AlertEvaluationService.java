package com.stockcat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.alert.EvaluationContext;
import com.stockcat.alert.RuleEvaluator;
import com.stockcat.alert.RuleEvaluatorRegistry;
import com.stockcat.common.ApiException;
import com.stockcat.domain.AlertEvent;
import com.stockcat.domain.AlertRule;
import com.stockcat.indicator.IndicatorCalculator.IndicatorValues;
import com.stockcat.web.dto.FundPremiumResponse;
import com.stockcat.web.dto.QuoteResponse;
import com.stockcat.mapper.AlertEventMapper;
import com.stockcat.mapper.AlertRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 规则评估与事件生成（P0.5 核心，P1.6 扩展指标类规则）。
 *
 * <ul>
 *   <li>行情类规则：仅 VALID 行情可触发（STALE/PARTIAL/INVALID/缺失一律跳过，不伪造事件），
 *       去重键 = rule-{id}-{触发分钟}</li>
 *   <li>指标类规则：只吃已收盘日 K（盘中传入的上下文没有历史，自然跳过），
 *       去重键 = rule-{id}-{交易日}，同一天重跑不会重复推送</li>
 *   <li>冷却期对两类都生效（last_triggered_at + cooldown）</li>
 *   <li>事件携带规则快照字段，规则删除/修改后历史仍可解释</li>
 * </ul>
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
     * 盘中入口：只有行情快照。指标类规则在这里会被跳过（缺历史，返回 empty）。
     *
     * <p>刻意不重载 {@code evaluate}：调用方传 null 时两个重载会歧义，而"传 null 行情"
     * 正是需要测试的路径。
     */
    public Optional<AlertEvent> evaluate(AlertRule rule, QuoteResponse quote, OffsetDateTime now) {
        return evaluateContext(rule, EvaluationContext.ofQuote(quote), now);
    }

    /**
     * 盘中入口（基金）：行情快照 + 溢价视图。溢价类规则读 context.premium()，
     * 其他规则忽略这个参数——多传一份数据不影响它们。
     */
    public Optional<AlertEvent> evaluate(AlertRule rule, QuoteResponse quote,
                                        FundPremiumResponse premium, OffsetDateTime now) {
        return evaluateContext(rule, EvaluationContext.ofQuote(quote, premium), now);
    }

    /**
     * 评估单条规则。返回生成的告警事件（未触发/跳过返回 empty）。
     */
    public Optional<AlertEvent> evaluateContext(AlertRule rule, EvaluationContext context, OffsetDateTime now) {
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
            return Optional.empty();
        }
        RuleEvaluator evaluator = registry.get(rule.getRuleType());
        boolean historyBased = evaluator.requiresHistory();

        // 1. 输入有效性：行情类要求 VALID 快照；指标类要求有历史（已收盘的日 K）
        if (historyBased) {
            if (context == null || !context.hasHistory()) {
                return Optional.empty();
            }
        } else if (context == null || context.quote() == null
                || !"VALID".equals(context.quote().dataStatus()) || context.quote().lastPrice() == null) {
            return Optional.empty();
        }

        // 2. 冷却检查（cooldownSeconds，0 表示每次满足都推送）
        int cooldown = rule.getCooldownSeconds() == null ? 1800 : rule.getCooldownSeconds();
        if (cooldown > 0 && rule.getLastTriggeredAt() != null
                && rule.getLastTriggeredAt().plusSeconds(cooldown).isAfter(now)) {
            return Optional.empty();
        }

        // 3. 评估
        JsonNode params = parse(rule.getParamsJson());
        if (!evaluator.evaluate(params, context)) {
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
        event.setObservedPrice(context.latestPrice());
        event.setObservedChangePercent(context.quote() == null ? null : context.quote().changePercent());
        event.setObservedDetail(historyBased || context.hasPremium() ? detailJson(context) : null);
        event.setQuoteMarketTime(context.quote() == null ? null : context.quote().marketTime());
        event.setTriggeredAt(triggeredAt);
        event.setDeduplicationKey(historyBased
                ? "rule-" + rule.getId() + "-" + context.latestTradeDate()
                : "rule-" + rule.getId() + "-" + triggeredAt.truncatedTo(ChronoUnit.MINUTES).toInstant());

        int inserted = eventMapper.insert(event);
        if (inserted == 0) {
            log.debug("事件被去重抑制: rule={} key={}", rule.getId(), event.getDeduplicationKey());
            return Optional.empty();
        }
        ruleMapper.updateLastTriggered(rule.getId(), triggeredAt);
        log.info("告警触发: rule={}({}) {} observed={} detail={}", rule.getName(), rule.getRuleType(),
                rule.getSymbol(), event.getObservedPrice(), event.getObservedDetail());
        try {
            var notification = notificationService.dispatch(event);
            log.info("通知状态: {} ({})", notification.getStatus(), notification.getId());
        } catch (Exception e) {
            log.error("通知派发异常(不影响事件): event={} err={}", event.getId(), e.getMessage());
        }
        return Optional.of(event);
    }

    /**
     * 事件明细：指标类把触发当根 K 线的指标值存下来，溢价类把溢价率与基准一起存下来。
     *
     * <p>不存明细的话，一周后回看事件只能看到"MACD 金叉"或"溢价 ≥ 3%"，
     * 却没法和当时的数据对上；K 线一旦被除权修正、净值一旦被更正，历史值必须留在事件里。
     * 溢价尤其如此：同一条"溢价 3%"，基准是实时 IOPV 还是三天前的净值，含义完全不同。
     */
    private String detailJson(EvaluationContext context) {
        IndicatorValues values = context.latest();
        Map<String, Object> detail = new LinkedHashMap<>();
        if (context.latestTradeDate() != null) {
            detail.put("tradeDate", context.latestTradeDate().toString());
        }
        detail.put("close", context.latestPrice());
        if (values != null) {
            putIfPresent(detail, "dif", values.dif());
            putIfPresent(detail, "dea", values.dea());
            putIfPresent(detail, "macdHist", values.macdHist());
            putIfPresent(detail, "rsi6", values.rsi6());
            putIfPresent(detail, "rsi12", values.rsi12());
            putIfPresent(detail, "rsi24", values.rsi24());
            putIfPresent(detail, "k", values.k());
            putIfPresent(detail, "d", values.d());
            putIfPresent(detail, "j", values.j());
        }
        if (context.hasPremium()) {
            Map<String, Object> premium = new LinkedHashMap<>();
            premium.put("premiumRate", context.premium().premiumRate());
            premium.put("premiumBasis", context.premium().premiumBasis());
            premium.put("premiumAsOf", context.premium().premiumAsOf());
            putIfPresent(premium, "price", context.premium().price());
            putIfPresent(premium, "iopv", context.premium().iopv());
            putIfPresent(premium, "nav", context.premium().nav());
            if (context.premium().navDate() != null) {
                premium.put("navDate", context.premium().navDate().toString());
            }
            if (context.premium().navStaleDays() != null) {
                premium.put("navStaleDays", context.premium().navStaleDays());
            }
            detail.put("premium", premium);
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            // 明细写不出来不能挡住告警本身：宁可少一份上下文，也要把事件发出去
            log.warn("事件明细序列化失败，按 null 存: {}", e.getMessage());
            return null;
        }
    }

    private static void putIfPresent(Map<String, Object> map, String key, Double value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private JsonNode parse(String paramsJson) {
        try {
            return objectMapper.readTree(paramsJson == null ? "{}" : paramsJson);
        } catch (Exception e) {
            throw ApiException.validationError("规则参数不是合法 JSON");
        }
    }
}
