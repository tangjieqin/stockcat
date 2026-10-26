package com.stockcat.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockcat.common.ApiException;

/**
 * 价格类规则公共实现：高于/低于阈值（含边界），基于最新价。
 */
abstract class AbstractPriceEvaluator implements RuleEvaluator {

    @Override
    public void validateParams(JsonNode params) {
        JsonNode threshold = params.get("threshold");
        if (threshold == null || !threshold.isNumber()) {
            throw ApiException.validationError(ruleType() + " 需要数值参数 {\"threshold\": number}");
        }
    }

    protected abstract boolean compare(double lastPrice, double threshold);

    @Override
    public boolean evaluate(JsonNode params, EvaluationContext context) {
        if (context == null || context.quote() == null || context.quote().lastPrice() == null) {
            return false;
        }
        double threshold = params.get("threshold").asDouble();
        return compare(context.quote().lastPrice(), threshold);
    }
}
