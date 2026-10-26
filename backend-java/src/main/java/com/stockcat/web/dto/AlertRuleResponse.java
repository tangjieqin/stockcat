package com.stockcat.web.dto;

import com.stockcat.domain.AlertRule;

import java.time.OffsetDateTime;

public record AlertRuleResponse(
        Long id,
        String name,
        Long instrumentId,
        String symbol,
        String instrumentName,
        String ruleType,
        Object params,
        Integer cooldownMinutes,
        Boolean enabled,
        OffsetDateTime lastTriggeredAt,
        OffsetDateTime createdAt
) {
    public static AlertRuleResponse from(AlertRule r) {
        Object params = null;
        try {
            params = new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getParamsJson());
        } catch (Exception ignored) {
            // params 已在创建时校验过，理论上不会失败
        }
        return new AlertRuleResponse(r.getId(), r.getName(), r.getInstrumentId(), r.getSymbol(),
                r.getInstrumentName(), r.getRuleType(), params, r.getCooldownMinutes(),
                r.getEnabled(), r.getLastTriggeredAt(), r.getCreatedAt());
    }
}
