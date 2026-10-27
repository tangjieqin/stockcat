package com.stockcat.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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

    /** 生成 AI 草稿：只给日期，数据由 Agent 通过只读工具自取（缺省为今天，Asia/Shanghai）。 */
    public record AiDraftRequest(LocalDate date) {
    }

    /**
     * 保存 AI 草稿。内容可能被用户先改过，所以正文由前端回传；
     * {@code generatedAt / model / dataAsOf / limitations} 是溯源信息，原样带回落库，
     * 不允许前端改写——否则「数据截至何时」就失去了可信度。
     */
    public record SaveRecapDraftRequest(
            @NotNull LocalDate recapDate,
            @NotBlank String title,
            String marketSummary,
            String sectorNotes,
            @NotBlank String content,
            OffsetDateTime generatedAt,
            String model,
            OffsetDateTime dataAsOf,
            List<String> limitations
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

    /**
     * 日记 AI 整理请求。
     *
     * <p>{@code nodeId} 有值时用库里的内容兜底，但 {@code title}/{@code content} 优先——
     * 用户在编辑器里改了还没保存时，整理的就该是眼前这一版。
     */
    public record DiaryRefineRequest(Long nodeId, String title, String content) {
    }

    /** 日记 AI 回顾请求：keyword 必填，limit 缺省 5、上限 10。 */
    public record DiaryReviewRequest(String keyword, Integer limit) {
    }

    public record DeleteResult(long deletedDocCount) {
    }
}
