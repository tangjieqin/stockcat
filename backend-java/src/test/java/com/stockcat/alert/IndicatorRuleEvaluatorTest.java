package com.stockcat.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.common.ApiException;
import com.stockcat.domain.KlineDaily;
import com.stockcat.indicator.IndicatorCalculator.IndicatorValues;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 指标类规则评估器（P1.6）：穿越语义、预热边界、参数校验。
 *
 * <p>重点不是"数值算得对"（那是 P1.3 的事），而是<b>什么算触发</b>：
 * 只有"上一根还在另一侧、本根到了这一侧"才算；贴着走、长期在一边、预热未完成都不算。
 */
class IndicatorRuleEvaluatorTest {

    private final ObjectMapper om = new ObjectMapper();

    private final MacdGoldenCrossEvaluator macdGolden = new MacdGoldenCrossEvaluator();
    private final MacdDeathCrossEvaluator macdDeath = new MacdDeathCrossEvaluator();
    private final KdjGoldenCrossEvaluator kdjGolden = new KdjGoldenCrossEvaluator();
    private final KdjDeathCrossEvaluator kdjDeath = new KdjDeathCrossEvaluator();
    private final RsiCrossAboveEvaluator rsiAbove = new RsiCrossAboveEvaluator();
    private final RsiCrossBelowEvaluator rsiBelow = new RsiCrossBelowEvaluator();

    @Test
    void macdGoldenCrossFiresWhenDifGoesFromBelowToAbove() {
        // 上一根 DIF -3 < DEA -2，本根 DIF 1 > DEA 0.5 → 金叉
        assertThat(macdGolden.evaluate(params("{}"), context(macd(-3, -2), macd(1, 0.5)))).isTrue();
    }

    @Test
    void macdGoldenCrossDoesNotFireWhenAlreadyAboveOrOnlyTouching() {
        // 一直在上方：不是"穿越"，不该每天推一条
        assertThat(macdGolden.evaluate(params("{}"), context(macd(10, 5), macd(12, 6)))).isFalse();
        // 刚好相等不算穿上去（含等号的一侧留给"上一根≤、本根>"）
        assertThat(macdGolden.evaluate(params("{}"), context(macd(5, 5), macd(5, 5)))).isFalse();
    }

    @Test
    void macdDeathCrossFiresWhenDifGoesFromAboveToBelow() {
        assertThat(macdDeath.evaluate(params("{}"), context(macd(10, 5), macd(12, 13)))).isTrue();
        assertThat(macdDeath.evaluate(params("{}"), context(macd(-1, -5), macd(-2, -4)))).isFalse();
    }

    @Test
    void crossRulesIgnoreWarmupValuesInsteadOfFiring() {
        // 预热段 dif/dea 为 null：不能当作 0 参与比较，否则首根就"金叉"了
        IndicatorValues empty = new IndicatorValues(null, null, null, null, null, null, null, null, null);
        assertThat(macdGolden.evaluate(params("{}"), context(empty, empty))).isFalse();
        assertThat(kdjGolden.evaluate(params("{}"), context(empty, kdj(50, 40)))).isFalse();
    }

    @Test
    void kdjCrossesUseKAndD() {
        assertThat(kdjGolden.evaluate(params("{}"), context(kdj(20, 25), kdj(30, 27)))).isTrue();
        assertThat(kdjDeath.evaluate(params("{}"), context(kdj(30, 27), kdj(25, 28)))).isTrue();
        assertThat(kdjGolden.evaluate(params("{}"), context(kdj(30, 27), kdj(31, 28)))).isFalse();
    }

    @Test
    void rsiCrossesThresholdWithConfiguredPeriod() {
        JsonNode crossing = params("{\"period\": 6, \"threshold\": 80}");
        assertThat(rsiAbove.evaluate(crossing, context(rsi(78.0), rsi(82.0)))).isTrue();
        assertThat(rsiAbove.evaluate(crossing, context(rsi(85.0), rsi(88.0)))).isFalse();
        assertThat(rsiBelow.evaluate(crossing, context(rsi(85.0), rsi(78.0)))).isTrue();
        // 换周期后取的是另一列，值不同就不该触发
        JsonNode period24 = params("{\"period\": 24, \"threshold\": 80}");
        assertThat(rsiAbove.evaluate(period24, context(rsi(78.0), rsi(82.0)))).isFalse();
    }

    @Test
    void rsiParamsAreValidatedAtCreationTime() {
        assertThatThrownBy(() -> rsiAbove.validateParams(params("{\"period\": 9, \"threshold\": 80}")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("period");
        assertThatCode(() -> rsiAbove.validateParams(params("{\"threshold\": 80}")))
                .as("period 缺省为 6，是合法的")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> rsiAbove.validateParams(params("{\"period\": 6, \"threshold\": 120}")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("0~100");
        assertThatThrownBy(() -> rsiAbove.validateParams(params("{\"period\": 6}")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("threshold");
    }

    @Test
    void crossRulesRejectUnexpectedParams() {
        // 交叉类规则没有阈值：传了 threshold 说明选错了类型，应当报错而不是默默忽略
        assertThatThrownBy(() -> macdGolden.validateParams(params("{\"threshold\": 10}")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不接受参数");
        assertThatCode(() -> macdGolden.validateParams(params("{}"))).doesNotThrowAnyException();
    }

    @Test
    void allIndicatorRulesDeclareTheirHistoryRequirement() {
        assertThat(macdGolden.requiresHistory()).isTrue();
        assertThat(macdGolden.requiredBars(params("{}"))).isEqualTo(35);
        assertThat(kdjGolden.requiredBars(params("{}"))).isEqualTo(10);
        assertThat(rsiAbove.requiredBars(params("{\"period\": 6, \"threshold\": 80}"))).isEqualTo(8);
        assertThat(rsiAbove.requiredBars(params("{\"period\": 24, \"threshold\": 80}"))).isEqualTo(26);
        // 行情类规则不该被指标任务捞走
        assertThat(new PriceAboveEvaluator().requiresHistory()).isFalse();
    }

    // ---- helpers ----

    private JsonNode params(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static EvaluationContext context(IndicatorValues prev, IndicatorValues cur) {
        KlineDaily first = bar(LocalDate.of(2026, 10, 23), 100.0);
        KlineDaily second = bar(LocalDate.of(2026, 10, 26), 101.0);
        return EvaluationContext.ofHistory(List.of(first, second), List.of(prev, cur));
    }

    private static KlineDaily bar(LocalDate date, double close) {
        KlineDaily bar = new KlineDaily();
        bar.setTradeDate(date);
        bar.setOpen(close);
        bar.setHigh(close);
        bar.setLow(close);
        bar.setClose(close);
        return bar;
    }

    private static IndicatorValues macd(double dif, double dea) {
        return new IndicatorValues(dif, dea, 2 * (dif - dea), null, null, null, null, null, null);
    }

    private static IndicatorValues kdj(double k, double d) {
        return new IndicatorValues(null, null, null, null, null, null, k, d, 3 * k - 2 * d);
    }

    /** 只设置 RSI6，RSI12/24 给 50（不满足阈值），用于验证"换周期取的是另一列" */
    private static IndicatorValues rsi(Double value) {
        return new IndicatorValues(null, null, null, value, 50.0, 50.0, null, null, null);
    }
}
