package com.stockcat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.alert.EvaluationContext;
import com.stockcat.alert.RuleEvaluator;
import com.stockcat.alert.RuleEvaluatorRegistry;
import com.stockcat.domain.AlertRule;
import com.stockcat.domain.Instrument;
import com.stockcat.domain.KlineDaily;
import com.stockcat.indicator.IndicatorCalculator;
import com.stockcat.mapper.AlertRuleMapper;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.mapper.KlineDailyMapper;
import com.stockcat.web.dto.IndicatorAlertSummary;
import com.stockcat.web.dto.IndicatorAlertSummary.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标类告警评估（P1.6）。
 *
 * <p>与行情类告警的关键区别：
 * <ul>
 *   <li><b>只在收盘后跑</b>（默认 15:40，紧跟 15:35 的 K 线同步）。盘中当日 K 线未收盘，
 *       DIF 会在一天内反复穿越 DEA，用半根 K 线判金叉会出现"上午推了金叉、收盘没有金叉"的事故。</li>
 *   <li><b>数据来自库，不来自上游</b>。K 线没同步就是"数据不足"，如实报告，不现场去抓——
 *       否则同一天两次评估可能用不同的数据，事件无法复现。</li>
 *   <li><b>穿越语义 + 按交易日去重</b>：同一天重复跑不会重复推送，补跑安全。</li>
 * </ul>
 */
@Service
public class IndicatorAlertService {

    private static final Logger log = LoggerFactory.getLogger(IndicatorAlertService.class);

    private final AlertRuleMapper ruleMapper;
    private final KlineDailyMapper klineMapper;
    private final InstrumentMapper instrumentMapper;
    private final IndicatorCalculator calculator;
    private final AlertEvaluationService evaluationService;
    private final RuleEvaluatorRegistry registry;
    private final ObjectMapper objectMapper;
    private final String adjust;
    private final int bars;

    public IndicatorAlertService(AlertRuleMapper ruleMapper, KlineDailyMapper klineMapper,
                                InstrumentMapper instrumentMapper, IndicatorCalculator calculator,
                                AlertEvaluationService evaluationService, RuleEvaluatorRegistry registry,
                                ObjectMapper objectMapper,
                                @Value("${stockcat.alert.indicator.adjust:qfq}") String adjust,
                                @Value("${stockcat.alert.indicator.bars:250}") int bars) {
        this.ruleMapper = ruleMapper;
        this.klineMapper = klineMapper;
        this.instrumentMapper = instrumentMapper;
        this.calculator = calculator;
        this.evaluationService = evaluationService;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.adjust = adjust;
        this.bars = Math.max(35, Math.min(bars, KlineSyncService.MAX_LIMIT));
    }

    /** 评估全部标的的指标类规则（收盘后任务走这里，手动补跑也走这里）。 */
    public IndicatorAlertSummary evaluateAll() {
        return evaluate(null);
    }

    /**
     * @param instrumentId 为 null 时评估所有启用中的指标类规则
     */
    public IndicatorAlertSummary evaluate(Long instrumentId) {
        long start = System.currentTimeMillis();
        List<AlertRule> rules = loadRules(instrumentId);

        // 按标的归组：同一只票只查一次 K 线、只算一次指标
        Map<Long, List<AlertRule>> byInstrument = new LinkedHashMap<>();
        for (AlertRule rule : rules) {
            byInstrument.computeIfAbsent(rule.getInstrumentId(), k -> new ArrayList<>()).add(rule);
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<RuleResult> results = new ArrayList<>();
        int triggered = 0;
        for (Map.Entry<Long, List<AlertRule>> entry : byInstrument.entrySet()) {
            Long iid = entry.getKey();
            List<KlineDaily> series = loadSeries(iid);
            List<IndicatorCalculator.IndicatorValues> indicators = calculator.calculate(series);
            EvaluationContext context = EvaluationContext.ofHistory(series, indicators);
            LocalDate tradeDate = context.latestTradeDate();
            Instrument instrument = instrumentMapper.findById(iid);
            String symbol = instrument == null ? null : instrument.getSymbol();

            for (AlertRule rule : entry.getValue()) {
                RuleResult result = evaluateOne(rule, symbol, context, tradeDate, now);
                results.add(result);
                if ("TRIGGERED".equals(result.status())) {
                    triggered++;
                }
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("指标告警评估完成: 标的 {} 条规则 {} 触发 {} 耗时 {}ms",
                byInstrument.size(), results.size(), triggered, elapsed);
        return new IndicatorAlertSummary(byInstrument.size(), results.size(), triggered, elapsed, adjust, results);
    }

    private RuleResult evaluateOne(AlertRule rule, String symbol, EvaluationContext context,
                                   LocalDate tradeDate, OffsetDateTime now) {
        RuleEvaluator evaluator;
        JsonNode params;
        int required;
        try {
            evaluator = registry.get(rule.getRuleType());
            params = objectMapper.readTree(rule.getParamsJson() == null ? "{}" : rule.getParamsJson());
            required = evaluator.requiredBars(params);
        } catch (Exception e) {
            return result(rule, symbol, "ERROR", tradeDate, 0, context.size(), e.getMessage());
        }

        if (context.bars().isEmpty()) {
            return result(rule, symbol, "SKIPPED_NO_KLINES", null, required, 0,
                    "该标的还没有日 K，先执行 POST /api/v1/instruments/" + rule.getInstrumentId() + "/history/sync");
        }
        if (context.size() < required) {
            return result(rule, symbol, "SKIPPED_INSUFFICIENT_BARS", tradeDate, required, context.size(),
                    "K 线不足：" + rule.getRuleType() + " 需要 " + required + " 根，当前 " + context.size() + " 根");
        }

        try {
            boolean fired = evaluationService.evaluateContext(rule, context, now).isPresent();
            if (fired) {
                return result(rule, symbol, "TRIGGERED", tradeDate, required, context.size(),
                        "已生成告警事件并派发通知");
            }
            return result(rule, symbol, "NOT_TRIGGERED", tradeDate, required, context.size(),
                    "最近两根没有出现穿越信号；同一交易日重复评估会被去重键抑制");
        } catch (Exception e) {
            return result(rule, symbol, "ERROR", tradeDate, required, context.size(), e.getMessage());
        }
    }

    private List<AlertRule> loadRules(Long instrumentId) {
        List<AlertRule> all = instrumentId == null
                ? ruleMapper.selectAllEnabled()
                : ruleMapper.selectPage(instrumentId, true, 0, 500);
        List<AlertRule> indicatorRules = new ArrayList<>();
        for (AlertRule rule : all) {
            try {
                if (registry.get(rule.getRuleType()).requiresHistory()) {
                    indicatorRules.add(rule);
                }
            } catch (Exception e) {
                // 历史遗留的未知类型：跳过即可，不能让一条坏规则挡住整轮评估
                log.warn("跳过类型无法识别的规则 {} ({}): {}", rule.getId(), rule.getRuleType(), e.getMessage());
            }
        }
        return indicatorRules;
    }

    private List<KlineDaily> loadSeries(Long instrumentId) {
        List<KlineDaily> rows = new ArrayList<>(klineMapper.selectRecent(instrumentId, adjust, null, bars));
        Collections.reverse(rows);   // 指标必须按时间升序计算
        return rows;
    }

    private static RuleResult result(AlertRule rule, String symbol, String status, LocalDate tradeDate,
                                     int required, int available, String message) {
        return new RuleResult(rule.getId(), rule.getName(), symbol, rule.getRuleType(),
                status, tradeDate, required, available, message);
    }
}
