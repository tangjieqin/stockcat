package com.stockcat.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 市场复盘（对应 docs/DATABASE.md §3.11）：每交易日一篇，含结构化分区字段。
 */
public class MarketRecap {

    private Long id;
    private LocalDate recapDate;
    private String title;
    private String marketSummary;
    private String sectorNotes;
    private String content;
    /** related_alert_event_ids JSONB 的字符串形式 */
    private String relatedAlertEventIds;
    private String status;   // MANUAL / AI_DRAFT
    /** AI 草稿溯源信息（V8）：手工创建的行全部为 null；AI_DRAFT 确认转 MANUAL 后仍保留。 */
    private OffsetDateTime aiGeneratedAt;
    private String aiModel;
    private OffsetDateTime aiDataAsOf;
    /** ai_limitations JSONB 的字符串形式（字符串数组） */
    private String aiLimitations;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getRecapDate() {
        return recapDate;
    }

    public void setRecapDate(LocalDate recapDate) {
        this.recapDate = recapDate;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMarketSummary() {
        return marketSummary;
    }

    public void setMarketSummary(String marketSummary) {
        this.marketSummary = marketSummary;
    }

    public String getSectorNotes() {
        return sectorNotes;
    }

    public void setSectorNotes(String sectorNotes) {
        this.sectorNotes = sectorNotes;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRelatedAlertEventIds() {
        return relatedAlertEventIds;
    }

    public void setRelatedAlertEventIds(String relatedAlertEventIds) {
        this.relatedAlertEventIds = relatedAlertEventIds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getAiGeneratedAt() {
        return aiGeneratedAt;
    }

    public void setAiGeneratedAt(OffsetDateTime aiGeneratedAt) {
        this.aiGeneratedAt = aiGeneratedAt;
    }

    public String getAiModel() {
        return aiModel;
    }

    public void setAiModel(String aiModel) {
        this.aiModel = aiModel;
    }

    public OffsetDateTime getAiDataAsOf() {
        return aiDataAsOf;
    }

    public void setAiDataAsOf(OffsetDateTime aiDataAsOf) {
        this.aiDataAsOf = aiDataAsOf;
    }

    public String getAiLimitations() {
        return aiLimitations;
    }

    public void setAiLimitations(String aiLimitations) {
        this.aiLimitations = aiLimitations;
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
