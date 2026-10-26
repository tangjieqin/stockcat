package com.stockcat.web.dto;

import jakarta.validation.constraints.Min;

import java.util.Map;

/**
 * PATCH /alert-rules/{id}：只允许 name/params/cooldownMinutes/enabled。
 * 不允许换标的或 ruleType（需删除重建，历史事件快照不受影响）。
 */
public record UpdateAlertRuleRequest(
        String name,
        Map<String, Object> params,
        @Min(0) Integer cooldownMinutes,
        Boolean enabled
) {
}
