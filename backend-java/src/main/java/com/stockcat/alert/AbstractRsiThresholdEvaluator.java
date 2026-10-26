package com.stockcat.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockcat.common.ApiException;
import com.stockcat.indicator.IndicatorCalculator.IndicatorValues;

/**
 * RSI 穿越阈值类规则：参数 {@code {"period": 6, "threshold": 80}}。
 *
 * <p>同样是<b>穿越</b>语义（上一根不满足、本根满足），不是"超过就每天推一次"：
 * RSI 一旦进入超买区常常连续多日，按状态触发会变成每天一条早安问候。
 * 代价要说清楚：规则新增时如果 RSI 已经在 85，不会立即补发；等下一次上穿才会提醒。
 *
 * <p>{@code period} 只允许 6 / 12 / 24——这三个是接口固定输出的列。
 * 允许任意周期就意味着查询时现算新的 RSI 序列，与"指标口径只有一处定义"冲突。
 */
abstract class AbstractRsiThresholdEvaluator implements RuleEvaluator {

    static final int DEFAULT_PERIOD = 6;
    static final double MIN_THRESHOLD = 0;
    static final double MAX_THRESHOLD = 100;

    @Override
    public final boolean requiresHistory() {
        return true;
    }

    @Override
    public void validateParams(JsonNode params) {
        int period = period(params);
        if (!IndicatorValues.RSI_PERIODS.contains(period)) {
            throw ApiException.validationError(ruleType() + " 的 period 只支持 "
                    + IndicatorValues.RSI_PERIODS + "，收到: " + period);
        }
        JsonNode threshold = params == null ? null : params.get("threshold");
        if (threshold == null || !threshold.isNumber()) {
            throw ApiException.validationError(ruleType() + " 需要参数 {\"period\": 6, \"threshold\": 80}");
        }
        double value = threshold.asDouble();
        if (value < MIN_THRESHOLD || value > MAX_THRESHOLD) {
            throw ApiException.validationError(ruleType() + " 的 threshold 必须在 0~100 之间（RSI 是百分数），收到: " + value);
        }
    }

    @Override
    public int requiredBars(JsonNode params) {
        // 需要 period+1 根才有第一个 RSI，再要一根判断"上一根"
        return period(params) + 2;
    }

    @Override
    public final boolean evaluate(JsonNode params, EvaluationContext context) {
        if (context == null || context.size() < 2) {
            return false;
        }
        int period = period(params);
        double threshold = params.get("threshold").asDouble();
        Double cur = rsi(context.latest(), period);
        Double prev = rsi(context.previous(), period);
        if (cur == null || prev == null) {
            return false;
        }
        return isAbove() ? (prev <= threshold && cur > threshold)
                : (prev >= threshold && cur < threshold);
    }

    /** true=上穿阈值，false=下穿阈值 */
    protected abstract boolean isAbove();

    private static Double rsi(IndicatorValues values, int period) {
        return values == null ? null : values.rsi(period);
    }

    private static int period(JsonNode params) {
        JsonNode node = params == null ? null : params.get("period");
        return node == null || !node.isNumber() ? DEFAULT_PERIOD : node.asInt();
    }
}
