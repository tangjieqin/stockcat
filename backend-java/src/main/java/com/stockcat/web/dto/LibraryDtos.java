package com.stockcat.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 资料库接口的请求与响应结构（对应 docs/API.md §7.2）。 */
public final class LibraryDtos {

    private LibraryDtos() {
    }

    /**
     * 新建资料。{@code sourceType} 只接受 PASTE / FILE；URL 在表结构里预留，接口暂不开，
     * 因为没有验证过的抓取能力（见 PLAN_P3 D1）。
     */
    public record CreateDocumentRequest(
            @NotBlank(message = "标题不能为空")
            @Size(max = 300, message = "标题不超过 300 字")
            String title,
            String sourceType,
            String sourceRef,
            String publisher,
            LocalDate publishedAt,
            @NotBlank(message = "正文不能为空")
            String content,
            List<Long> instrumentIds,
            String note) {
    }

    /** 列表项：不带正文，列表页不需要全文。 */
    public record DocumentSummary(
            Long id, String title, String sourceType, String sourceRef, String publisher,
            LocalDate publishedAt, OffsetDateTime importedAt, Integer charCount,
            Integer chunkCount, String status) {
    }

    public record ChunkView(
            Long id, Integer seq, String heading, String content,
            Integer charStart, Integer charEnd) {
    }

    /** 详情：带正文与全部片段（片段用于高亮与引用定位）。 */
    public record DocumentDetail(
            Long id, String title, String sourceType, String sourceRef, String publisher,
            LocalDate publishedAt, OffsetDateTime importedAt, Integer charCount,
            Integer chunkCount, String status, String note, String content,
            List<Long> instrumentIds, List<ChunkView> chunks) {
    }

    public record PageResult(long total, int page, int size, List<DocumentSummary> items) {
    }

    /**
     * 检索命中（P3.2）。
     *
     * <p>带 {@code charStart}/{@code charEnd} 与 {@code seq}：前端点击后在正文里定位到这一段，
     * 而不是只给一句 snippet 让用户自己找。{@code matchedTerms} 用于高亮，
     * 也解释了"这条为什么被召回"。{@code titleMatched} 标出"只有标题命中"的情况——
     * 否则用户会疑惑"这段正文里根本没有这个词"。
     */
    public record SearchHit(
            Long chunkId, Long documentId, String documentTitle, Integer seq, String heading,
            String snippet, Integer charStart, Integer charEnd,
            String sourceType, String sourceRef, String publisher,
            LocalDate publishedAt, OffsetDateTime importedAt,
            int score, List<String> matchedTerms, boolean titleMatched) {
    }

    /**
     * @param terms 实际参与检索的整词；前端可以据此提示"把'白酒渠道库存'拆成了这些词"
     */
    public record SearchResult(String keyword, List<String> terms, List<SearchHit> hits) {
    }
}
