package com.stockcat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.alert.RuleEvaluator;
import com.stockcat.alert.RuleEvaluatorRegistry;
import com.stockcat.assistant.AssistantClient;
import com.stockcat.common.ApiException;
import com.stockcat.domain.AlertEvent;
import com.stockcat.domain.Instrument;
import com.stockcat.domain.KlineDaily;
import com.stockcat.indicator.IndicatorCalculator;
import com.stockcat.mapper.AlertEventMapper;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.mapper.KlineDailyMapper;
import com.stockcat.web.dto.QuoteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警事件的 AI 解释（P2 场景二）：把「事件快照 + 当前状态」交给 Python 解释。
 *
 * <p>为什么由 Java 组装数据、Python 只做解释：
 * <ul>
 *   <li>事件快照只存在于库里（{@code observed_detail}），Python 不连数据库；</li>
 *   <li>当前行情与指标的口径由 Java 统一定义，避免"页面看到一个值、AI 说另一个值"；</li>
 *   <li>Python 不做任何数值计算，也就不存在"用今天的 K 线重算一次当年指标"的风险。</li>
 * </ul>
 *
 * <p><b>关键取舍</b>：只把库里真实存在的东西发过去。事件没有 {@code observed_detail}
 * 就发 null，绝不在 Java 侧补算——补算出来的数字看着合理，但从未在触发时存在过，
 * 是这类功能最容易犯也最难发现的错误。
 */
@Service
public class AlertExplainService {

    private static final Logger log = LoggerFactory.getLogger(AlertExplainService.class);

    private final AlertEventMapper eventMapper;
    private final InstrumentMapper instrumentMapper;
    private final QuoteService quoteService;
    private final KlineDailyMapper klineMapper;
    private final IndicatorCalculator calculator;
    private final RuleEvaluatorRegistry registry;
    private final AssistantClient assistantClient;
    private final ObjectMapper objectMapper;
    private final String adjust;
    private final int bars;

    public AlertExplainService(AlertEventMapper eventMapper, InstrumentMapper instrumentMapper,
                               QuoteService quoteService, KlineDailyMapper klineMapper,
                               IndicatorCalculator calculator, RuleEvaluatorRegistry registry,
                               AssistantClient assistantClient, ObjectMapper objectMapper,
                               @Value("${stockcat.alert.indicator.adjust:qfq}") String adjust,
                               @Value("${stockcat.alert.indicator.bars:250}") int bars) {
        this.eventMapper = eventMapper;
        this.instrumentMapper = instrumentMapper;
        this.quoteService = quoteService;
        this.klineMapper = klineMapper;
        this.calculator = calculator;
        this.registry = registry;
        this.assistantClient = assistantClient;
        this.objectMapper = objectMapper;
        this.adjust = adjust;
        this.bars = bars;
    }

    public Map<String, Object> explain(Long eventId) {
        AlertEvent event = eventMapper.findById(eventId);
        if (event == null) {
            throw ApiException.notFound("事件不存在: id=" + eventId);
        }
        Instrument instrument = instrumentMapper.findById(event.getInstrumentId());
        String ruleType = event.getRuleTypeSnapshot();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", snapshot(event, instrument));
        payload.put("current", currentState(event.getInstrumentId(), ruleType));
        log.debug("解释告警事件: id={} type={}", eventId, ruleType);
        return assistantClient.explainAlert(payload);
    }

    /** 事件快照：字段与 AlertEvent 一一对应，不改写、不推算。 */
    private Map<String, Object> snapshot(AlertEvent event, Instrument instrument) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", event.getId());
        m.put("symbol", event.getSymbol());
        m.put("name", instrument == null ? null : instrument.getName());
        m.put("ruleName", event.getRuleNameSnapshot());
        m.put("ruleType", event.getRuleTypeSnapshot());
        m.put("params", readJson(event.getConditionParamsSnapshot()));
        m.put("observedPrice", event.getObservedPrice());
        m.put("observedChangePercent", event.getObservedChangePercent());
        m.put("observedDetail", readJson(event.getObservedDetail()));
        m.put("triggeredAt", iso(event.getTriggeredAt()));
        m.put("quoteMarketTime", iso(event.getQuoteMarketTime()));
        return m;
    }

    /**
     * 当前状态：行情必有（可能为空），指标只有指标类规则才查。
     *
     * <p>价格类规则查一遍 250 根 K 线只为算指标，是纯浪费；而指标类规则不查就答不了
     * "现在怎么样了"。这个判断依赖评估器的 {@code requiresHistory()}，与告警评估同一口径。
     */
    private Map<String, Object> currentState(Long instrumentId, String ruleType) {
        Map<String, Object> m = new LinkedHashMap<>();
        QuoteResponse quote = quoteService.latest(List.of(instrumentId)).get(instrumentId);
        if (quote == null) {
            m.put("lastPrice", null);
            m.put("changePercent", null);
            m.put("dataStatus", null);
            m.put("marketTime", null);
            m.put("collectedAt", null);
        } else {
            m.put("lastPrice", quote.lastPrice());
            m.put("changePercent", quote.changePercent());
            m.put("dataStatus", quote.dataStatus());
            m.put("marketTime", iso(quote.marketTime()));
            m.put("collectedAt", iso(quote.collectedAt()));
        }

        m.put("barsAvailable", 0);
        if (!requiresHistory(ruleType)) {
            return m;
        }
        List<KlineDaily> series = loadSeries(instrumentId);
        m.put("barsAvailable", series.size());
        if (series.isEmpty()) {
            return m;
        }
        List<IndicatorCalculator.IndicatorValues> values = calculator.calculate(series);
        if (values.isEmpty()) {
            return m;
        }
        IndicatorCalculator.IndicatorValues latest = values.get(values.size() - 1);
        m.put("latestTradeDate", series.get(series.size() - 1).getTradeDate().toString());
        Map<String, Object> indicators = new LinkedHashMap<>();
        put(indicators, "dif", latest.dif());
        put(indicators, "dea", latest.dea());
        put(indicators, "macdHist", latest.macdHist());
        put(indicators, "rsi6", latest.rsi6());
        put(indicators, "rsi12", latest.rsi12());
        put(indicators, "rsi24", latest.rsi24());
        put(indicators, "k", latest.k());
        put(indicators, "d", latest.d());
        put(indicators, "j", latest.j());
        m.put("indicators", indicators);
        return m;
    }

    private boolean requiresHistory(String ruleType) {
        try {
            RuleEvaluator evaluator = registry.get(ruleType);
            return evaluator.requiresHistory();
        } catch (Exception e) {
            // 历史遗留的未知类型：当作行情类处理，不因为一条坏规则让解释整体失败
            log.debug("规则类型无法识别，按行情类处理: {}", ruleType);
            return false;
        }
    }

    private List<KlineDaily> loadSeries(Long instrumentId) {
        List<KlineDaily> rows = new ArrayList<>(klineMapper.selectRecent(instrumentId, adjust, null, bars));
        Collections.reverse(rows);   // 指标必须按时间升序计算
        return rows;
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isNull() ? null : node;
        } catch (Exception e) {
            // 快照写坏时如实给 null：宁可说"没有明细"，也不能杜撰一份
            log.warn("事件快照不是合法 JSON，按 null 发送: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 时间统一转 ISO-8601 字符串。
     * 不依赖 Jackson 的时间模块（AssistantClient 用的是裸 ObjectMapper，
     * 传 OffsetDateTime 会直接序列化失败），也避免 "2026-10-26T14:00Z" 这种省略秒的写法。
     */
    private static String iso(OffsetDateTime value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static void put(Map<String, Object> map, String key, Double value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
