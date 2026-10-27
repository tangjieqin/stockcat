package com.stockcat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.alert.RuleEvaluator;
import com.stockcat.alert.RuleEvaluatorRegistry;
import com.stockcat.assistant.AssistantClient;
import com.stockcat.common.ApiException;
import com.stockcat.domain.AlertEvent;
import com.stockcat.domain.Instrument;
import com.stockcat.domain.KlineDaily;
import com.stockcat.indicator.IndicatorCalculator;
import com.stockcat.indicator.IndicatorCalculator.IndicatorValues;
import com.stockcat.mapper.AlertEventMapper;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.mapper.KlineDailyMapper;
import com.stockcat.web.dto.QuoteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警解释的数据组装：**只发库里真实存在的字段**，指标类才查 K 线，时间统一 ISO。
 */
class AlertExplainServiceTest {

    private static final OffsetDateTime TRIGGERED = OffsetDateTime.of(
            2026, 10, 26, 14, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime MARKET_TIME = OffsetDateTime.of(
            2026, 10, 26, 7, 0, 0, 0, ZoneOffset.UTC);

    private AlertEventMapper eventMapper;
    private InstrumentMapper instrumentMapper;
    private QuoteService quoteService;
    private KlineDailyMapper klineMapper;
    private IndicatorCalculator calculator;
    private RuleEvaluatorRegistry registry;
    private AssistantClient assistantClient;
    private AlertExplainService service;

    @BeforeEach
    void setUp() {
        eventMapper = mock(AlertEventMapper.class);
        instrumentMapper = mock(InstrumentMapper.class);
        quoteService = mock(QuoteService.class);
        klineMapper = mock(KlineDailyMapper.class);
        calculator = mock(IndicatorCalculator.class);
        registry = mock(RuleEvaluatorRegistry.class);
        assistantClient = mock(AssistantClient.class);
        service = new AlertExplainService(eventMapper, instrumentMapper, quoteService, klineMapper,
                calculator, registry, assistantClient, new ObjectMapper(), "qfq", 250);

        Instrument instrument = new Instrument();
        instrument.setId(2L);
        instrument.setSymbol("600519");
        instrument.setName("贵州茅台");
        when(instrumentMapper.findById(2L)).thenReturn(instrument);
        when(assistantClient.explainAlert(any())).thenReturn(Map.of("mode", "rule_based"));
    }

    @Test
    void missing_event_returns_not_found() {
        when(eventMapper.findById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.explain(99L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("事件不存在");
        verify(assistantClient, never()).explainAlert(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void price_event_sends_snapshot_without_touching_klines() {
        when(eventMapper.findById(1L)).thenReturn(priceEvent());
        RuleEvaluator evQ = evaluator(false);
        when(registry.get("PRICE_ABOVE")).thenReturn(evQ);
        when(quoteService.latest(List.of(2L))).thenReturn(Map.of(2L, quote()));

        service.explain(1L);

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(assistantClient).explainAlert(captor.capture());
        Map<String, Object> payload = captor.getValue();
        Map<String, Object> event = (Map<String, Object>) payload.get("event");
        Map<String, Object> current = (Map<String, Object>) payload.get("current");

        assertThat(event.get("symbol")).isEqualTo("600519");
        assertThat(event.get("name")).isEqualTo("贵州茅台");
        assertThat(event.get("ruleType")).isEqualTo("PRICE_ABOVE");
        assertThat(event.get("params")).isNotNull();       // JSON 字符串已解析成对象
        assertThat(event.get("observedPrice")).isEqualTo(1023.5);
        assertThat(current.get("lastPrice")).isEqualTo(1030.0);
        assertThat(current.get("dataStatus")).isEqualTo("VALID");
        // 行情类规则不查 K 线：省一次 250 根的查询
        verify(klineMapper, never()).selectRecent(any(), anyString(), any(), anyInt());
        assertThat(current.get("barsAvailable")).isEqualTo(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void indicator_event_loads_klines_and_attaches_latest_values() {
        when(eventMapper.findById(2L)).thenReturn(macdEvent());
        RuleEvaluator evH = evaluator(true);
        when(registry.get("MACD_GOLDEN_CROSS")).thenReturn(evH);
        when(quoteService.latest(List.of(2L))).thenReturn(Map.of(2L, quote()));
        when(klineMapper.selectRecent(eq(2L), eq("qfq"), any(), eq(250))).thenReturn(List.of(bar(), bar()));
        when(calculator.calculate(anyList())).thenReturn(List.of(values(0.02), values(0.0123)));

        service.explain(2L);

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(assistantClient).explainAlert(captor.capture());
        Map<String, Object> current = (Map<String, Object>) captor.getValue().get("current");

        assertThat(current.get("barsAvailable")).isEqualTo(2);
        assertThat(current.get("latestTradeDate")).isEqualTo("2026-10-26");
        Map<String, Object> indicators = (Map<String, Object>) current.get("indicators");
        // 取最后一根（升序后的最新值），不是第一根
        assertThat(indicators.get("dif")).isEqualTo(0.0123);
        assertThat(indicators.get("rsi6")).isEqualTo(78.5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void null_observed_detail_stays_null() {
        AlertEvent event = priceEvent();
        event.setObservedDetail(null);
        when(eventMapper.findById(3L)).thenReturn(event);
        RuleEvaluator evQ = evaluator(false);
        when(registry.get("PRICE_ABOVE")).thenReturn(evQ);
        when(quoteService.latest(List.of(2L))).thenReturn(Map.of());

        service.explain(3L);

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(assistantClient).explainAlert(captor.capture());
        Map<String, Object> snapshot = (Map<String, Object>) captor.getValue().get("event");
        // 关键：不补算、不编一份明细出来
        assertThat(snapshot.get("observedDetail")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void timestamps_are_iso_strings_with_seconds() {
        when(eventMapper.findById(1L)).thenReturn(priceEvent());
        RuleEvaluator evQ = evaluator(false);
        when(registry.get("PRICE_ABOVE")).thenReturn(evQ);
        when(quoteService.latest(List.of(2L))).thenReturn(Map.of(2L, quote()));

        service.explain(1L);

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(assistantClient).explainAlert(captor.capture());
        Map<String, Object> event = (Map<String, Object>) captor.getValue().get("event");
        // 裸 ObjectMapper 序列化不了 OffsetDateTime，必须自己转字符串
        assertThat(event.get("triggeredAt")).isEqualTo("2026-10-26T14:00:00Z");
        assertThat(event.get("quoteMarketTime")).isEqualTo("2026-10-26T07:00:00Z");
    }

    @Test
    @SuppressWarnings("unchecked")
    void absent_quote_sends_nulls_instead_of_zeros() {
        when(eventMapper.findById(1L)).thenReturn(priceEvent());
        RuleEvaluator evQ = evaluator(false);
        when(registry.get("PRICE_ABOVE")).thenReturn(evQ);
        when(quoteService.latest(List.of(2L))).thenReturn(Map.of());

        service.explain(1L);

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(assistantClient).explainAlert(captor.capture());
        Map<String, Object> current = (Map<String, Object>) captor.getValue().get("current");
        assertThat(current.get("lastPrice")).isNull();
        assertThat(current.get("marketTime")).isNull();
        assertThat(current.get("changePercent")).isNull();
    }

    @Test
    void unknown_rule_type_is_treated_as_intraday() {
        when(eventMapper.findById(4L)).thenReturn(priceEvent());
        when(registry.get("PRICE_ABOVE")).thenThrow(ApiException.validationError("未知类型"));
        when(quoteService.latest(List.of(2L))).thenReturn(Map.of(2L, quote()));

        // 不因为一条历史遗留规则让整个解释失败
        assertThat(service.explain(4L)).containsKey("mode");
        verify(klineMapper, never()).selectRecent(any(), anyString(), any(), anyInt());
    }

    // ------------------------------------------------------------------ 构造

    private static AlertEvent priceEvent() {
        AlertEvent e = new AlertEvent();
        e.setId(1L);
        e.setInstrumentId(2L);
        e.setSymbol("600519");
        e.setRuleNameSnapshot("茅台 价格≥1000");
        e.setRuleTypeSnapshot("PRICE_ABOVE");
        e.setConditionParamsSnapshot("{\"threshold\":1000}");
        e.setObservedPrice(1023.5);
        e.setObservedChangePercent(1.8);
        e.setObservedDetail(null);
        e.setTriggeredAt(TRIGGERED);
        e.setQuoteMarketTime(MARKET_TIME);
        return e;
    }

    private static AlertEvent macdEvent() {
        AlertEvent e = priceEvent();
        e.setId(2L);
        e.setRuleNameSnapshot("茅台 MACD 金叉");
        e.setRuleTypeSnapshot("MACD_GOLDEN_CROSS");
        e.setConditionParamsSnapshot("{}");
        e.setObservedDetail("{\"tradeDate\":\"2026-10-26\",\"close\":1500.0,\"dif\":0.0123,\"dea\":0.0101}");
        return e;
    }

    private static QuoteResponse quote() {
        return new QuoteResponse(1030.0, 1020.0, 1.2, null, null,
                MARKET_TIME, MARKET_TIME.plusSeconds(20), "VALID", "TENCENT");
    }

    private static RuleEvaluator evaluator(boolean requiresHistory) {
        RuleEvaluator e = mock(RuleEvaluator.class);
        when(e.requiresHistory()).thenReturn(requiresHistory);
        return e;
    }

    private static KlineDaily bar() {
        KlineDaily b = new KlineDaily();
        b.setTradeDate(LocalDate.of(2026, 10, 26));
        b.setClose(1500.0);
        return b;
    }

    private static IndicatorValues values(double dif) {
        return new IndicatorValues(dif, 0.0101, 0.0044, 78.5, 70.2, 65.0, 55.0, 50.0, 65.0);
    }
}
