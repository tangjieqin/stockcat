package com.stockcat.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 检索命中行（P3.2）：片段 + 所属资料的元信息，一次查询取全，避免前端为每条命中再查一次文档。
 *
 * <p>{@code content} 是整段原文，只在服务端用来截 snippet 与取命中词，不返回给前端。
 */
public class LibrarySearchRow {

    private Long chunkId;
    private Long documentId;
    private Integer seq;
    private String heading;
    private String content;
    private Integer charStart;
    private Integer charEnd;
    private String documentTitle;
    private String sourceType;
    private String sourceRef;
    private String publisher;
    private LocalDate publishedAt;
    private OffsetDateTime importedAt;
    private Integer score;

    public Long getChunkId() {
        return chunkId;
    }

    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getCharStart() {
        return charStart;
    }

    public void setCharStart(Integer charStart) {
        this.charStart = charStart;
    }

    public Integer getCharEnd() {
        return charEnd;
    }

    public void setCharEnd(Integer charEnd) {
        this.charEnd = charEnd;
    }

    public String getDocumentTitle() {
        return documentTitle;
    }

    public void setDocumentTitle(String documentTitle) {
        this.documentTitle = documentTitle;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getPublisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    public LocalDate getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDate publishedAt) {
        this.publishedAt = publishedAt;
    }

    public OffsetDateTime getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(OffsetDateTime importedAt) {
        this.importedAt = importedAt;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }
}
