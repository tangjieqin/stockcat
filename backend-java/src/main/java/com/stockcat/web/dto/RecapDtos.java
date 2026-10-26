package com.stockcat.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public class RecapDtos {

    public record CreateRecapRequest(
            @NotNull LocalDate recapDate,
            @NotBlank String title,
            String marketSummary,
            String sectorNotes,
            @NotBlank String content,
            List<Long> relatedAlertEventIds
    ) {
    }

    public record UpdateRecapRequest(
            String title,
            String marketSummary,
            String sectorNotes,
            String content,
            List<Long> relatedAlertEventIds
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiaryNodeView(
            Long id,
            Long parentId,
            String nodeType,
            String title,
            Integer sortOrder,
            List<DiaryNodeView> children
    ) {
    }

    public record DiaryNodeDetail(
            Long id,
            Long parentId,
            String nodeType,
            String title,
            String content,
            Object relatedInstrumentIds,
            Integer sortOrder,
            java.time.OffsetDateTime updatedAt
    ) {
    }

    public record CreateDiaryNodeRequest(
            Long parentId,
            @NotBlank String nodeType,
            @NotBlank String title,
            String content,
            List<Long> relatedInstrumentIds
    ) {
    }

    /** PATCH：title/content/relatedInstrumentIds 或移动 {parentId, sortOrder} */
    public record UpdateDiaryNodeRequest(
            String title,
            String content,
            List<Long> relatedInstrumentIds,
            Long parentId,
            Integer sortOrder
    ) {
    }

    public record DiarySearchHit(Long id, String title, List<String> path) {
    }

    public record DeleteResult(long deletedDocCount) {
    }
}
