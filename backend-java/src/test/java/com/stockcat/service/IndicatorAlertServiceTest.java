package com.stockcat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.alert.ChangePercentAboveEvaluator;
import com.stockcat.alert.ChangePercentBelowEvaluator;
import com.stockcat.alert.KdjDeathCrossEvaluator;
import com.stockcat.alert.KdjGoldenCrossEvaluator;
import com.stockcat.alert.MacdDeathCrossEvaluator;
import com.stockcat.alert.MacdGoldenCrossEvaluator;
import com.stockcat.alert.PriceAboveEvaluator;
import com.stockcat.alert.PriceBelowEvaluator;
import com.stockcat.alert.RsiCrossAboveEvaluator;
import com.stockcat.alert.RsiCrossBelowEvaluator;
import com.stockcat.alert.RuleEvaluatorRegistry;
import com.stockcat.domain.AlertEvent;
import com.stockcat.domain.AlertRule;
import com.stockcat.domain.Instrument;
import com.stockcat.domain.KlineDaily;
import com.stockcat.indicator.IndicatorCalculator;
import com.stockcat.mapper.AlertEventMapper;
import com.stockcat.mapper.AlertRuleMapper;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.mapper.KlineDailyMapper;
import com.stockcat.support.KlineFixtures;
import com.stockcat.web.dto.IndicatorAlertSummary;
import com.stockcat.web.dto.IndicatorAlertSummary.RuleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 指标告警评估的服务级验收（P1.6）。
 *
 * <p>用真实 K 线夹具（600519 前复权 120 根）+ 真实指标计算 + 真实评估链路，只把 DB 换成 mock。
 * 交叉点不是编的：43 根时最后一根（2026-07-01）确实是 MACD 金叉，19 根时最后一根（2026-05-27）
 * 确实是 KDJ 金叉——那是先用独立实现扫出来的位置。
 */
class IndicatorAlertServiceTest {

    private static final Long IID = 1L;

    private AlertRuleMapper ruleMapper;
    private KlineDailyMapper klineMapper;
    private InstrumentMapper instrumentMapper;
    private AlertEventMapper eventMapper;
    private AlertEvaluationService evaluationService;
    private IndicatorAlertService service;

    @BeforeEach
    void setup() {
        ruleMapper = Mockito.mock(AlertRuleMapper.class);
        klineMapper = Mockito.mock(KlineDailyMapper.class);
        instrumentMapper = Mockito.mock(InstrumentMapper.class);
        eventMapper = Mockito.mock(AlertEventMapper.class);
        when(eventMapper.insert(any())).thenReturn(1);

        RuleEvaluatorRegistry registry = new RuleEvaluatorRegistry(List.of(
                new PriceAboveEvaluator(), new PriceBelowEvaluator(),
                new ChangePercentAboveEvaluator(), new ChangePercentBelowEvaluator(),
                new MacdGoldenCrossEvaluator(), new MacdDeathCrossEvaluator(),
                new KdjGoldenCrossEvaluator(), new KdjDeathCrossEvaluator(),
                new RsiCrossAboveEvaluator(), new RsiCrossBelowEvaluator()));

        evaluationService = new AlertEvaluationService(registry, ruleMapper, eventMapper,
                new ObjectMapper(), Mockito.mock(NotificationService.class));
        service = new IndicatorAlertService(ruleMapper, klineMapper, instrumentMapper,
                new IndicatorCalculator(), evaluationService, registry, new ObjectMapper(), "qfq", 250);

        Instrument instrument = new Instrument();
        instrument.setId(IID);
        instrument.setSymbol("600519");
        instrument.setName("贵州茅台");
        when(instrumentMapper.findById(IID)).thenReturn(instrument);
    }

    @Test
    void macdGoldenCrossAtLatestBarTriggersAndRecordsDetail() {
        stubRules(rule(7L, "茅台 MACD 金叉", "MACD_GOLDEN_CROSS", "{}"));
        stubSeries(KlineFixtures.head(43));

        IndicatorAlertSummary summary = service.evaluate(IID);

        assertThat(summary.rules()).isEqualTo(1);
        assertThat(summary.triggered()).isEqualTo(1);
        RuleResult result = summary.results().get(0);
        assertThat(result.status()).isEqualTo("TRIGGERED");
        assertThat(result.tradeDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(result.symbol()).isEqualTo("600519");
        assertThat(result.availableBars()).isEqualTo(43);

        AlertEvent event = capturedEvent();
        assertThat(event.getDeduplicationKey()).isEqualTo("rule-7-2026-07-01");
        assertThat(event.getRuleTypeSnapshot()).isEqualTo("MACD_GOLDEN_CROSS");
        assertThat(event.getObservedPrice()).isNotNull();
        // 明细里必须有当根指标值：否则事后无法解释"当时是多少"
        assertThat(event.getObservedDetail()).contains("\"dif\"").contains("\"dea\"")
                .contains("2026-07-01");
    }

    @Test
    void sameTradeDayReevaluationIsSuppressedByDedupKey() {
        stubRules(rule(7L, "茅台 MACD 金叉", "MACD_GOLDEN_CROSS", "{}"));
        stubSeries(KlineFixtures.head(43));
        // 第一次插入成功，第二次被唯一键挡掉（真实 DB 行为是 ON CONFLICT DO NOTHING → 返回 0）
        when(eventMapper.insert(any())).thenReturn(1, 0);

        assertThat(service.evaluate(IID).results().get(0).status()).isEqualTo("TRIGGERED");
        assertThat(service.evaluate(IID).results().get(0).status()).isEqualTo("NOT_TRIGGERED");
    }

    @Test
    void kdjGoldenCrossAlsoWorks() {
        stubRules(rule(8L, "茅台 KDJ 金叉", "KDJ_GOLDEN_CROSS", "{}"));
        stubSeries(KlineFixtures.head(19));

        RuleResult result = service.evaluate(IID).results().get(0);

        assertThat(result.status()).isEqualTo("TRIGGERED");
        assertThat(result.tradeDate()).isEqualTo(LocalDate.of(2026, 5, 27));
    }

    @Test
    void insufficientBarsIsReportedAsSkippedNotAsNotTriggered() {
        stubRules(rule(7L, "茅台 MACD 金叉", "MACD_GOLDEN_CROSS", "{}"));
        stubSeries(KlineFixtures.head(20));

        RuleResult result = service.evaluate(IID).results().get(0);

        // "数据不够"和"没交叉"是两件事，前端提示不同
        assertThat(result.status()).isEqualTo("SKIPPED_INSUFFICIENT_BARS");
        assertThat(result.requiredBars()).isEqualTo(35);
        assertThat(result.availableBars()).isEqualTo(20);
        assertThat(result.message()).contains("35").contains("20");
    }

    @Test
    void missingKlineIsReportedWithSyncHint() {
        stubRules(rule(7L, "茅台 MACD 金叉", "MACD_GOLDEN_CROSS", "{}"));
        stubSeries(List.of());

        RuleResult result = service.evaluate(IID).results().get(0);

        assertThat(result.status()).isEqualTo("SKIPPED_NO_KLINES");
        assertThat(result.message()).contains("history/sync");
    }

    @Test
    void quoteRulesAreNotEvaluatedByIndicatorJob() {
        stubRules(rule(1L, "价格提醒", "PRICE_ABOVE", "{\"threshold\": 1000}"));

        IndicatorAlertSummary summary = service.evaluate(IID);

        assertThat(summary.rules()).isZero();
        assertThat(summary.results()).isEmpty();
    }

    @Test
    void unknownLegacyRuleTypeDoesNotBreakWholeRound() {
        AlertRule broken = rule(9L, "历史脏规则", "LEGACY_UNKNOWN", "{}");
        AlertRule ok = rule(7L, "茅台 MACD 金叉", "MACD_GOLDEN_CROSS", "{}");
        stubRules(broken, ok);
        stubSeries(KlineFixtures.head(43));

        IndicatorAlertSummary summary = service.evaluate(IID);

        assertThat(summary.rules()).isEqualTo(1);
        assertThat(summary.results().get(0).status()).isEqualTo("TRIGGERED");
    }

    // ---- helpers ----

    private void stubRules(AlertRule... rules) {
        when(ruleMapper.selectPage(eq(IID), eq(true), anyInt(), anyInt())).thenReturn(List.of(rules));
        when(ruleMapper.selectAllEnabled()).thenReturn(List.of(rules));
    }

    private void stubSeries(List<KlineDaily> ascending) {
        List<KlineDaily> desc = new ArrayList<>(ascending);
        Collections.reverse(desc);
        when(klineMapper.selectRecent(eq(IID), eq("qfq"), isNull(), anyInt())).thenReturn(desc);
    }

    private AlertEvent capturedEvent() {
        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        Mockito.verify(eventMapper, Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private static AlertRule rule(Long id, String name, String type, String params) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setName(name);
        rule.setInstrumentId(IID);
        rule.setRuleType(type);
        rule.setParamsJson(params);
        rule.setCooldownSeconds(0);
        rule.setEnabled(true);
        rule.setSymbol("600519");
        return rule;
    }
}
