package com.stockcat.alert;

import com.stockcat.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评估器注册表：ruleType -> evaluator。未知类型在创建规则时即拒绝（避免死规则）。
 */
@Component
public class RuleEvaluatorRegistry {

    private final Map<String, RuleEvaluator> evaluators;

    public RuleEvaluatorRegistry(List<RuleEvaluator> list) {
        this.evaluators = list.stream()
                .collect(Collectors.toMap(RuleEvaluator::ruleType, e -> e));
    }

    public RuleEvaluator get(String ruleType) {
        RuleEvaluator evaluator = evaluators.get(ruleType);
        if (evaluator == null) {
            throw ApiException.validationError("未知或未支持的规则类型: " + ruleType
                    + "（当前支持: " + String.join(", ", evaluators.keySet()) + "）");
        }
        return evaluator;
    }
}
