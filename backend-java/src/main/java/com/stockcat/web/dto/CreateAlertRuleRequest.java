package com.stockcat.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateAlertRuleRequest(
        @NotBlank String name,
        @NotNull Long instrumentId,
        @NotBlank String ruleType,
        Map<String, Object> params,
        @Min(0) Integer cooldownMinutes,
        Boolean enabled
) {
}
