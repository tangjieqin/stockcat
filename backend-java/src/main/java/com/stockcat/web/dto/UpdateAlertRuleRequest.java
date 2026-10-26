package com.stockcat.web.dto;

import jakarta.validation.constraints.Min;

import java.util.Map;

/**
 * PATCH /alert-rules/{id}：允许 name/ruleType/params/cooldownMinutes/enabled。
 * 标的不允许换（需删除重建，历史事件快照不受影响）。
 */
public record UpdateAlertRuleRequest(
        String name,
        String ruleType,
        Map<String, Object> params,
        @Min(0) Integer cooldownMinutes,
        Boolean enabled
) {
}
