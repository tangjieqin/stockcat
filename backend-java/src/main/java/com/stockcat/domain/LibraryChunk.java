package com.stockcat.domain;

import java.time.OffsetDateTime;

/**
 * 资料片段（对应 V9 的 `library_chunk`，P3.1）。
 *
 * <p>检索与引用的最小单位。`charStart` / `charEnd` 指向原文的字符区间（左闭右开），
 * 引用时按这两个偏移回原文取完整片段——**不用检索时的截取结果**，
 * 截取会让引用在语义上失真（前后半句被切掉）。
 */
public class LibraryChunk {

    private Long id;
    private Long documentId;
    /** 文档内序号，从 1 开始 */
    private Integer seq;
    /** 所属标题；无标题时为 null */
    private String heading;
    private String content;
    private Integer charStart;
    private Integer charEnd;
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
