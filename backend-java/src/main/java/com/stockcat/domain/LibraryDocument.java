package com.stockcat.domain;

import java.time.OffsetDateTime;

/**
 * 资料文档（对应 V9 的 `library_document`，P3.1）。
 *
 * <p>一篇资料 = 一次导入。与日记的区别是：日记由用户撰写、可反复修改；
 * 资料是外部内容，**导入后正文不再编辑**（要改就重新导入，靠 hash 拦截重复）。
 */
public class LibraryDocument {

    private Long id;
    private String title;
    /** PASTE / FILE / URL */
    private String sourceType;
    /** 文件名或 URL；粘贴时为空 */
    private String sourceRef;
    private String publisher;
    /** 资料本身的发布日期（只到天，可能未知） */
    private java.time.LocalDate publishedAt;
    private OffsetDateTime importedAt;
    /** 规范化后的全文 */
    private String content;
    /** sha256(content) */
    private String contentHash;
    private Integer charCount;
    /** ACTIVE / ARCHIVED */
    private String status;
    private String note;
    /** 片段数：列表查询用子查询带出，不是表字段 */
    private Integer chunkCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

    public java.time.LocalDate getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(java.time.LocalDate publishedAt) {
        this.publishedAt = publishedAt;
    }

    public OffsetDateTime getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(OffsetDateTime importedAt) {
        this.importedAt = importedAt;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }
}
