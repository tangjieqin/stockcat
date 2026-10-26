package com.stockcat.alert;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 可插拔规则评估器（docs/DATABASE.md §3.5 / ARCHITECTURE 决策 10）。
 * 新增规则类型 = 新增一个 ruleType 值 + 一个实现类，不修改表结构。
 *
 * <p>输入统一为 {@link EvaluationContext}：
 * <ul>
 *   <li>{@link #requiresHistory()} = false：行情类规则，盘中每 30 秒评估，读 {@code ctx.quote()}</li>
 *   <li>{@link #requiresHistory()} = true：指标类规则，收盘后（15:40）评估，读 {@code ctx.bars()/indicators()}</li>
 * </ul>
 * 两类规则分开评估时间不是实现偷懒：盘中当日 K 线还没收盘，DIF 会在一天里反复穿越 DEA，
 * 用半根 K 线判金叉会出现"上午推了金叉、收盘却没有金叉"的告警。
 */
public interface RuleEvaluator {

    /** 规则类型标识，如 PRICE_ABOVE */
    String ruleType();

    /** 参数 schema 校验，不合法抛 ApiException(VALIDATION_ERROR) */
    void validateParams(JsonNode params);

    /** 是否需要历史 K 线。默认 false（P0 的四种行情规则）。 */
    default boolean requiresHistory() {
        return false;
    }

    /**
     * 是否需要基金参考数据（IOPV/净值/溢价）。默认 false。
     * 需要时评估任务会额外取一次溢价视图，且创建规则时会校验标的是 ETF/LOF。
     */
    default boolean requiresPremium() {
        return false;
    }

    /**
     * 该规则至少需要多少根 K 线才能判定；0 表示不适用（行情类规则）。
     * 用于把"数据不足"和"没触发"分开报告——两者对用户的含义完全不同。
     * 入参是规则参数，因为有些规则（如 RSI 周期）的要求随参数变化。
     */
    default int requiredBars(JsonNode params) {
        return 0;
    }

    /**
     * 评估是否触发。输入缺失（null / 历史不足）一律返回 false，不猜测。
     * 边界语义：行情类含边界（>= / <=，见 docs/UI.md 2.4）；指标类为"穿越"语义（上一根不满足、本根满足）。
     */
    boolean evaluate(JsonNode params, EvaluationContext context);
}
