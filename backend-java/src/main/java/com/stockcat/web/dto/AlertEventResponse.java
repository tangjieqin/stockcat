package com.stockcat.web.dto;

import com.stockcat.domain.AlertEvent;
import com.stockcat.domain.NotificationRecord;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 告警事件响应，对应 docs/API.md §5：含 paramsSnapshot/observedDetail 与通知记录。
 */
public record AlertEventResponse(
        Long id,
        Long instrumentId,
        String symbol,
        Long ruleId,
        String ruleName,
        String ruleType,
        Object paramsSnapshot,
        Double observedPrice,
        Double observedChangePercent,
        Object observedDetail,
        OffsetDateTime quoteMarketTime,
        OffsetDateTime triggeredAt,
        List<NotificationItem> notifications
) {

    public record NotificationItem(Long id, String channel, String status, String lastError,
                                   Integer attemptCount, OffsetDateTime sentAt) {
        static NotificationItem from(NotificationRecord r) {
            return new NotificationItem(r.getId(), r.getChannel(), r.getStatus(),
                    r.getLastErrorMessage(), r.getAttemptCount(), r.getSentAt());
        }
    }

    public static AlertEventResponse from(AlertEvent e, List<NotificationRecord> notifications) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Object params = readTree(mapper, e.getConditionParamsSnapshot());
        Object detail = readTree(mapper, e.getObservedDetail());
        return new AlertEventResponse(e.getId(), e.getInstrumentId(), e.getSymbol(), e.getRuleId(),
                e.getRuleNameSnapshot(), e.getRuleTypeSnapshot(), params,
                e.getObservedPrice(), e.getObservedChangePercent(), detail,
                e.getQuoteMarketTime(), e.getTriggeredAt(),
                notifications.stream().map(NotificationItem::from).toList());
    }

    private static Object readTree(com.fasterxml.jackson.databind.ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            return json;
        }
    }
}
