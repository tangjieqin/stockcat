package com.stockcat.domain;

import java.time.OffsetDateTime;

/**
 * 我的日记节点（对应 docs/DATABASE.md §3.12）：FOLDER/DOC 统一一棵树。
 */
public class DiaryNode {

    private Long id;
    private Long parentId;
    private String nodeType;   // FOLDER / DOC
    private String title;
    private String content;
    /** related_instrument_ids JSONB 的字符串形式 */
    private String relatedInstrumentIds;
    private Integer sortOrder;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRelatedInstrumentIds() {
        return relatedInstrumentIds;
    }

    public void setRelatedInstrumentIds(String relatedInstrumentIds) {
        this.relatedInstrumentIds = relatedInstrumentIds;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
