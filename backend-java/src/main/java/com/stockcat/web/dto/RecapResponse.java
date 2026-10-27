package com.stockcat.web.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.domain.MarketRecap;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 市场复盘响应（docs/API.md §7）。
 *
 * <p>{@code ai*} 四字段是溯源信息：手工创建的复盘为 null；由 AI 起草的复盘即使已确认为
 * MANUAL 也保留——它回答的是「这份复盘最初怎么来的、数据截至何时」，不是当前状态。
 * 前端据此在正文旁展示「AI 生成 / 数据截至 / 数据限制」，不可编辑。
 */
public record RecapResponse(
        Long id,
        LocalDate recapDate,
        String title,
        String marketSummary,
        String sectorNotes,
        String content,
        Object relatedAlertEventIds,
        String status,
        OffsetDateTime aiGeneratedAt,
        String aiModel,
        OffsetDateTime aiDataAsOf,
        Object aiLimitations,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static RecapResponse from(MarketRecap r) {
        return new RecapResponse(r.getId(), r.getRecapDate(), r.getTitle(), r.getMarketSummary(),
                r.getSectorNotes(), r.getContent(), parseJson(r.getRelatedAlertEventIds()),
                r.getStatus(), r.getAiGeneratedAt(), r.getAiModel(), r.getAiDataAsOf(),
                parseJson(r.getAiLimitations()), r.getCreatedAt(), r.getUpdatedAt());
    }

    /** JSONB 列以字符串读出，这里还原成 JSON；解析不了就原样返回，不吞掉内容。 */
    private static Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new ObjectMapper().readTree(raw);
        } catch (Exception ignored) {
            return raw;
        }
    }
}
