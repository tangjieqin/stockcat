package com.stockcat.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockcat.common.ApiException;
import com.stockcat.web.dto.QuoteResponse;
import org.springframework.stereotype.Component;

/**
 * 涨跌幅类规则公共实现：基于最新涨跌幅（百分数值，跌 20% 为 -20），含边界。
 */
abstract class AbstractChangePercentEvaluator implements RuleEvaluator {

    @Override
    public void validateParams(JsonNode params) {
        JsonNode threshold = params.get("threshold");
        if (threshold == null || !threshold.isNumber()) {
            throw ApiException.validationError(ruleType() + " 需要数值参数 {\"threshold\": number}（百分数值，跌 20% 为 -20）");
        }
    }

    protected abstract boolean compare(double changePercent, double threshold);

    @Override
    public boolean evaluate(JsonNode params, QuoteResponse quote) {
        if (quote == null || quote.changePercent() == null) {
            return false;
        }
        double threshold = params.get("threshold").asDouble();
        return compare(quote.changePercent(), threshold);
    }
}
