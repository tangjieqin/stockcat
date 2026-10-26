package com.stockcat.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockcat.common.ApiException;
import com.stockcat.indicator.IndicatorCalculator.IndicatorValues;

/**
 * 指标"两条线交叉"类规则的公共实现：MACD 的 DIF/DEA、KDJ 的 K/D。
 *
 * <p>判定用<b>穿越</b>语义，不是"满足即触发"：
 * <pre>
 *   金叉：上一根 快线 ≤ 慢线，本根 快线 &gt; 慢线
 *   死叉：上一根 快线 ≥ 慢线，本根 快线 &lt; 慢线
 * </pre>
 * 如果用"满足即触发"，DIF 在 DEA 上方连着一周就会每天推一条——那不是告警，是噪音。
 * 代价是"规则新增时已经交叉过"不会补发，这是正确行为：金叉发生在过去，现在提醒也没有意义。
 */
abstract class AbstractCrossEvaluator implements RuleEvaluator {

    @Override
    public final boolean requiresHistory() {
        return true;
    }

    /** 交叉类规则不需要参数；传了参数说明类型选错了，直接拒绝而不是静默忽略。 */
    @Override
    public void validateParams(JsonNode params) {
        if (params != null && params.isObject() && !params.isEmpty()) {
            throw ApiException.validationError(ruleType() + " 不接受参数，收到: " + params);
        }
    }

    @Override
    public final boolean evaluate(JsonNode params, EvaluationContext context) {
        if (context == null || context.size() < 2) {
            return false;
        }
        double[] cur = lines(context.latest());
        double[] prev = lines(context.previous());
        if (cur == null || prev == null) {
            return false;   // 预热不足：交给 requiredBars 报告，不当作"没触发"
        }
        return isGolden() ? crossedAbove(prev, cur) : crossedBelow(prev, cur);
    }

    /** @return {快线, 慢线}；任一为 null（预热不足）返回 null */
    protected abstract double[] lines(IndicatorValues values);

    /** true=金叉（向上穿），false=死叉（向下穿） */
    protected abstract boolean isGolden();

    static boolean crossedAbove(double[] prev, double[] cur) {
        return prev[0] <= prev[1] && cur[0] > cur[1];
    }

    static boolean crossedBelow(double[] prev, double[] cur) {
        return prev[0] >= prev[1] && cur[0] < cur[1];
    }
}
