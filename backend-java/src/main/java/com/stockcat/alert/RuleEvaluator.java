package com.stockcat.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockcat.web.dto.QuoteResponse;

/**
 * 可插拔规则评估器（docs/DATABASE.md §3.5 / ARCHITECTURE 决策 10）。
 * 新增规则类型 = 新增一个 ruleType 值 + 一个实现类，不修改表结构。
 */
public interface RuleEvaluator {

    /** 规则类型标识，如 PRICE_ABOVE */
    String ruleType();

    /** 参数 schema 校验，不合法抛 ApiException(VALIDATION_ERROR) */
    void validateParams(JsonNode params);

    /**
     * 评估是否触发。行情缺失字段（null）一律返回 false，不猜测。
     * 边界语义：含边界（>= / <=），见 docs/UI.md 2.4。
     */
    boolean evaluate(JsonNode params, QuoteResponse quote);
}
