package com.stockcat.web.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.domain.MarketRecap;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record RecapResponse(
        Long id,
        LocalDate recapDate,
        String title,
        String marketSummary,
        String sectorNotes,
        String content,
        Object relatedAlertEventIds,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static RecapResponse from(MarketRecap r) {
        Object ids = null;
        if (r.getRelatedAlertEventIds() != null && !r.getRelatedAlertEventIds().isBlank()) {
            try {
                ids = new ObjectMapper().readTree(r.getRelatedAlertEventIds());
            } catch (Exception ignored) {
                ids = r.getRelatedAlertEventIds();
            }
        }
        return new RecapResponse(r.getId(), r.getRecapDate(), r.getTitle(), r.getMarketSummary(),
                r.getSectorNotes(), r.getContent(), ids, r.getStatus(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
