package com.stockcat.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.common.ApiException;
import com.stockcat.domain.AlertEvent;
import com.stockcat.domain.AlertRule;
import com.stockcat.domain.QuoteSnapshot;
import com.stockcat.mapper.AlertEventMapper;
import com.stockcat.mapper.AlertRuleMapper;
import com.stockcat.service.AlertEvaluationService;
import com.stockcat.web.dto.QuoteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P0.5 验收单元测试：四种条件覆盖、边界含值、过期/无效行情不触发、冷却生效。
 */
class AlertEvaluationServiceTest {

    private AlertEventMapper eventMapper;
    private AlertRuleMapper ruleMapper;
    private AlertEvaluationService service;
    private final ObjectMapper om = new ObjectMapper();

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-26T10:00:00+08:00");

    @BeforeEach
    void setup() {
        eventMapper = Mockito.mock(AlertEventMapper.class);
        ruleMapper = Mockito.mock(AlertRuleMapper.class);
        AlertEvaluatorStubs stubs = new AlertEvaluatorStubs();
        when(eventMapper.insert(any())).thenReturn(1);
        service = new AlertEvaluationService(stubs.registry(), ruleMapper, eventMapper, om,
                Mockito.mock(com.stockcat.service.NotificationService.class));
    }

    /** 简单注册表容器 */
    static class AlertEvaluatorStubs {
        RuleEvaluatorRegistry registry() {
            return new RuleEvaluatorRegistry(java.util.List.of(
                    new PriceAboveEvaluator(), new PriceBelowEvaluator(),
                    new ChangePercentAboveEvaluator(), new ChangePercentBelowEvaluator(),
                    new MacdGoldenCrossEvaluator(), new PremiumAboveEvaluator()));
        }
    }

    private static AlertRule rule(String type, String params, Integer cooldown, OffsetDateTime lastTriggered) {
        AlertRule r = new AlertRule();
        r.setId(1L);
        r.setName("测试规则");
        r.setInstrumentId(100L);
        r.setRuleType(type);
        r.setParamsJson(params);
        r.setCooldownSeconds(cooldown);
        r.setEnabled(true);
        r.setLastTriggeredAt(lastTriggered);
        r.setSymbol("600519");
        return r;
    }

    private static QuoteResponse quote(Double lastPrice, Double changePercent, String status) {
        return new QuoteResponse(lastPrice, null, changePercent, null, null,
                NOW.minusMinutes(1), NOW, status, "test");
    }

    // ---- 四种条件 ----

    @Test
    void priceAboveTriggers() {
        Optional<AlertEvent> e = service.evaluate(rule("PRICE_ABOVE", "{\"threshold\":12.5}", 30, null),
                quote(12.60, 1.0, "VALID"), NOW);
        assertThat(e).isPresent();
        assertThat(e.get().getObservedPrice()).isEqualTo(12.60);
        assertThat(e.get().getRuleTypeSnapshot()).isEqualTo("PRICE_ABOVE");
        assertThat(e.get().getConditionParamsSnapshot()).isEqualTo("{\"threshold\":12.5}");
    }

    @Test
    void priceBelowTriggers() {
        assertThat(service.evaluate(rule("PRICE_BELOW", "{\"threshold\":10}", 30, null),
                quote(9.99, -0.5, "VALID"), NOW)).isPresent();
    }

    @Test
    void changePercentAboveTriggers() {
        assertThat(service.evaluate(rule("CHANGE_PERCENT_ABOVE", "{\"threshold\":5}", 30, null),
                quote(105.0, 5.2, "VALID"), NOW)).isPresent();
    }

    @Test
    void changePercentBelowTriggers() {
        // 跌破 20%：-20 为阈值，-20.5 触发
        assertThat(service.evaluate(rule("CHANGE_PERCENT_BELOW", "{\"threshold\":-20}", 30, null),
                quote(79.5, -20.5, "VALID"), NOW)).isPresent();
    }

    // ---- 边界含值 ----

    @Test
    void boundaryInclusive() {
        assertThat(service.evaluate(rule("PRICE_ABOVE", "{\"threshold\":12.5}", 30, null),
                quote(12.5, 0.0, "VALID"), NOW)).isPresent();
        assertThat(service.evaluate(rule("CHANGE_PERCENT_BELOW", "{\"threshold\":-20}", 30, null),
                quote(80.0, -20.0, "VALID"), NOW)).isPresent();
        assertThat(service.evaluate(rule("PRICE_ABOVE", "{\"threshold\":12.5}", 30, null),
                quote(12.49, 0.0, "VALID"), NOW)).isEmpty();
    }

    // ---- 过期/无效/缺失行情不触发 ----

    @Test
    void staleQuoteDoesNotTrigger() {
        assertThat(service.evaluate(rule("PRICE_ABOVE", "{\"threshold\":1}", 30, null),
                quote(99.0, 0.0, "STALE"), NOW)).isEmpty();
        assertThat(service.evaluate(rule("PRICE_ABOVE", "{\"threshold\":1}", 30, null),
                quote(99.0, 0.0, "INVALID"), NOW)).isEmpty();
        assertThat(service.evaluate(rule("PRICE_ABOVE", "{\"threshold\":1}", 30, null),
                quote(null, null, "VALID"), NOW)).isEmpty();
        assertThat(service.evaluate(rule("PRICE_ABOVE", "{\"threshold\":1}", 30, null),
                null, NOW)).isEmpty();
    }

    // ---- 冷却 ----

    @Test
    void cooldownSuppressesRepeatTrigger() {
        // cooldown 单位为秒：1800 = 30 分钟
        AlertRule r = rule("PRICE_ABOVE", "{\"threshold\":12.5}", 1800, NOW.minusMinutes(10));
        assertThat(service.evaluate(r, quote(12.6, 1.0, "VALID"), NOW)).isEmpty();

        // 冷却期已过 -> 可再次触发
        AlertRule r2 = rule("PRICE_ABOVE", "{\"threshold\":12.5}", 1800, NOW.minusMinutes(31));
        assertThat(service.evaluate(r2, quote(12.6, 1.0, "VALID"), NOW)).isPresent();
    }

    @Test
    void zeroCooldownAllowsImmediateRetrigger() {
        assertThat(service.evaluate(rule("PRICE_ABOVE", "{\"threshold\":12.5}", 0, NOW.minusSeconds(5)),
                quote(12.6, 1.0, "VALID"), NOW)).isPresent();
    }

    // ---- 指标类规则（P1.6） ----

    @Test
    void indicatorRuleIsSkippedWhenOnlyQuoteIsAvailable() {
        // 盘中任务只喂行情快照：指标规则必须安静跳过，不能拿半根 K 线去判金叉
        AlertRule r = rule("MACD_GOLDEN_CROSS", "{}", 0, null);

        assertThat(service.evaluate(r, quote(1300.0, 1.0, "VALID"), NOW)).isEmpty();
        Mockito.verify(eventMapper, Mockito.never()).insert(any());
    }

    @Test
    void indicatorRuleFiresOnHistoryContextWithTradingDayDedupKey() {
        AlertRule r = rule("MACD_GOLDEN_CROSS", "{}", 0, null);
        var bars = java.util.List.of(kline("2026-10-23"), kline("2026-10-26"));
        var indicators = java.util.List.of(
                new com.stockcat.indicator.IndicatorCalculator.IndicatorValues(-3.0, -2.0, -2.0,
                        null, null, null, null, null, null),
                new com.stockcat.indicator.IndicatorCalculator.IndicatorValues(1.0, 0.5, 1.0,
                        null, null, null, null, null, null));
        var context = EvaluationContext.ofHistory(bars, indicators);

        Optional<AlertEvent> event = service.evaluateContext(r, context, NOW);

        assertThat(event).isPresent();
        // 同一天重复跑不会重复推送：键里带交易日，不带分钟
        assertThat(event.get().getDeduplicationKey()).isEqualTo("rule-1-2026-10-26");
        assertThat(event.get().getObservedPrice()).isEqualTo(101.0);
        assertThat(event.get().getObservedDetail()).contains("\"dif\":1.0");
    }

    // ---- 溢价类规则（P1.5） ----

    @Test
    void premiumRuleFiresWithBasisInDetail() {
        AlertRule r = rule("PREMIUM_ABOVE", "{\"threshold\":2}", 0, null);
        com.stockcat.web.dto.FundPremiumResponse premium = new com.stockcat.web.dto.FundPremiumResponse(
                java.time.LocalDate.parse("2026-10-26"), 4.18, 4.0735,
                NOW.minusMinutes(2), 4.1797, java.time.LocalDate.parse("2026-10-23"), 3L,
                2.61, "IOPV", NOW.minusMinutes(2).toString(), "tencent-qt", "eastmoney-f10-lsjz", NOW);

        Optional<AlertEvent> event = service.evaluate(r, quote(4.18, 0.5, "VALID"), premium, NOW);

        assertThat(event).isPresent();
        // 溢价事件沿用分钟级去重（盘中可能反复穿越阈值），但明细里必须留下"拿什么算的"
        assertThat(event.get().getDeduplicationKey()).startsWith("rule-1-");
        assertThat(event.get().getObservedDetail()).contains("\"premiumBasis\":\"IOPV\"");
        assertThat(event.get().getObservedDetail()).contains("\"premiumRate\":2.61");
        assertThat(event.get().getObservedPrice()).isEqualTo(4.18);
    }

    @Test
    void premiumRuleWithoutPremiumDataIsSkipped() {
        AlertRule r = rule("PREMIUM_ABOVE", "{\"threshold\":2}", 0, null);

        // 没传溢价视图（老入口 / 股票 / 取数失败）：安静跳过，不拿价格当基准
        assertThat(service.evaluate(r, quote(4.18, 0.5, "VALID"), NOW)).isEmpty();
        assertThat(service.evaluate(r, quote(4.18, 0.5, "VALID"), null, NOW)).isEmpty();
        Mockito.verify(eventMapper, Mockito.never()).insert(any());
    }

    private static com.stockcat.domain.KlineDaily kline(String date) {
        var bar = new com.stockcat.domain.KlineDaily();
        bar.setTradeDate(java.time.LocalDate.parse(date));
        bar.setClose("2026-10-23".equals(date) ? 100.0 : 101.0);
        return bar;
    }

    // ---- 参数校验 ----

    @Test
    void evaluatorRejectsBadParams() {
        AbstractPriceEvaluator evaluator = new PriceAboveEvaluator();
        assertThatThrownBy(() -> evaluator.validateParams(om.valueToTree(java.util.Map.of())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("threshold");
    }
}
