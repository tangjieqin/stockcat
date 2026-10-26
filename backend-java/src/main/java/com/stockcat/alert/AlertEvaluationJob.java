package com.stockcat.alert;

import com.stockcat.domain.AlertRule;
import com.stockcat.service.AlertEvaluationService;
import com.stockcat.service.QuoteService;
import com.stockcat.service.TradingCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 告警评估任务（P0.5）。
 * - 仅交易时段运行（与采集共用 shouldCollectNow 判定）
 * - AtomicBoolean 防重入
 * - 每轮：启用规则 -> 批量取最新快照 -> 逐条评估（冷却/去重在 AlertEvaluationService）
 */
@Component
public class AlertEvaluationJob {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationJob.class);

    private final TradingCalendarService calendarService;
    private final QuoteService quoteService;
    private final com.stockcat.mapper.AlertRuleMapper ruleMapper;
    private final AlertEvaluationService evaluationService;
    private final RuleEvaluatorRegistry registry;
    private final com.stockcat.service.FundReferenceService fundReferenceService;
    private final com.stockcat.mapper.InstrumentMapper instrumentMapper;
    private final boolean evaluateEnabled;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public AlertEvaluationJob(TradingCalendarService calendarService,
                              QuoteService quoteService,
                              com.stockcat.mapper.AlertRuleMapper ruleMapper,
                              AlertEvaluationService evaluationService,
                              RuleEvaluatorRegistry registry,
                              com.stockcat.service.FundReferenceService fundReferenceService,
                              com.stockcat.mapper.InstrumentMapper instrumentMapper,
                              @org.springframework.beans.factory.annotation.Value("${stockcat.evaluate.enabled:true}") boolean evaluateEnabled) {
        this.calendarService = calendarService;
        this.quoteService = quoteService;
        this.ruleMapper = ruleMapper;
        this.evaluationService = evaluationService;
        this.registry = registry;
        this.fundReferenceService = fundReferenceService;
        this.instrumentMapper = instrumentMapper;
        this.evaluateEnabled = evaluateEnabled;
    }

    @Scheduled(fixedDelayString = "${stockcat.evaluate.interval-seconds:30}000", initialDelayString = "8000")
    public void run() {
        if (!evaluateEnabled) {
            return;
        }
        if (!calendarService.shouldCollectNow()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("上一轮评估尚未结束，本轮跳过（防重入）");
            return;
        }
        try {
            doEvaluate();
        } catch (Exception e) {
            log.error("评估任务异常: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    void doEvaluate() {
        List<AlertRule> rules = ruleMapper.selectAllEnabled();
        if (rules.isEmpty()) {
            return;
        }
        LinkedHashSet<Long> instrumentIds = new LinkedHashSet<>();
        rules.forEach(r -> instrumentIds.add(r.getInstrumentId()));
        Map<Long, com.stockcat.web.dto.QuoteResponse> latest = quoteService.latest(new ArrayList<>(instrumentIds));
        Map<Long, com.stockcat.web.dto.FundPremiumResponse> premiums = loadPremiums(rules);

        OffsetDateTime now = OffsetDateTime.now();
        int triggered = 0;
        for (AlertRule rule : rules) {
            try {
                if (evaluationService.evaluate(rule, latest.get(rule.getInstrumentId()),
                        premiums.get(rule.getInstrumentId()), now).isPresent()) {
                    triggered++;
                }
            } catch (Exception e) {
                log.warn("规则 {} 评估失败: {}", rule.getId(), e.getMessage());
            }
        }
        if (triggered > 0) {
            log.info("本轮评估触发 {} 条告警（规则共 {} 条）", triggered, rules.size());
        }
    }

    /**
     * 只有存在溢价类规则时才查基金参考数据：每轮都多查两次库，对 P0 的纯行情规则是纯粹的浪费。
     * 单个规则的类型不认识也不能拖垮整轮评估（历史遗留的脏 ruleType 不该让告警全停）。
     */
    private Map<Long, com.stockcat.web.dto.FundPremiumResponse> loadPremiums(List<AlertRule> rules) {
        LinkedHashSet<Long> premiumIds = new LinkedHashSet<>();
        for (AlertRule rule : rules) {
            try {
                if (registry.get(rule.getRuleType()).requiresPremium()) {
                    premiumIds.add(rule.getInstrumentId());
                }
            } catch (Exception e) {
                log.warn("规则 {} 类型不可用（{}），本轮跳过", rule.getId(), rule.getRuleType());
            }
        }
        if (premiumIds.isEmpty()) {
            return Map.of();
        }
        try {
            var instruments = instrumentMapper.selectByIds(new ArrayList<>(premiumIds));
            return fundReferenceService.latestPremiums(instruments);
        } catch (Exception e) {
            // 溢价取不到时溢价类规则不触发（而不是崩掉整轮），行情类告警照常
            log.warn("溢价数据获取失败，本轮溢价类规则跳过: {}", e.getMessage());
            return Map.of();
        }
    }
}
