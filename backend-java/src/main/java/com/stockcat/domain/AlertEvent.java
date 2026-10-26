package com.stockcat.domain;

import java.time.OffsetDateTime;

/**
 * 告警事件（对应 docs/DATABASE.md §3.6）。含规则快照字段：规则删除/修改后历史事件仍可解释。
 */
public class AlertEvent {

    private Long id;
    private Long ruleId;
    private Long instrumentId;
    private String ruleNameSnapshot;
    private String ruleTypeSnapshot;
    private String conditionParamsSnapshot;
    private Double observedPrice;
    private Double observedChangePercent;
    private String observedDetail;
    private OffsetDateTime quoteMarketTime;
    private OffsetDateTime triggeredAt;
    private String deduplicationKey;
    // join instrument
    private String symbol;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public Long getInstrumentId() {
        return instrumentId;
    }

    public void setInstrumentId(Long instrumentId) {
        this.instrumentId = instrumentId;
    }

    public String getRuleNameSnapshot() {
        return ruleNameSnapshot;
    }

    public void setRuleNameSnapshot(String ruleNameSnapshot) {
        this.ruleNameSnapshot = ruleNameSnapshot;
    }

    public String getRuleTypeSnapshot() {
        return ruleTypeSnapshot;
    }

    public void setRuleTypeSnapshot(String ruleTypeSnapshot) {
        this.ruleTypeSnapshot = ruleTypeSnapshot;
    }

    public String getConditionParamsSnapshot() {
        return conditionParamsSnapshot;
    }

    public void setConditionParamsSnapshot(String conditionParamsSnapshot) {
        this.conditionParamsSnapshot = conditionParamsSnapshot;
    }

    public Double getObservedPrice() {
        return observedPrice;
    }

    public void setObservedPrice(Double observedPrice) {
        this.observedPrice = observedPrice;
    }

    public Double getObservedChangePercent() {
        return observedChangePercent;
    }

    public void setObservedChangePercent(Double observedChangePercent) {
        this.observedChangePercent = observedChangePercent;
    }

    public String getObservedDetail() {
        return observedDetail;
    }

    public void setObservedDetail(String observedDetail) {
        this.observedDetail = observedDetail;
    }

    public OffsetDateTime getQuoteMarketTime() {
        return quoteMarketTime;
    }

    public void setQuoteMarketTime(OffsetDateTime quoteMarketTime) {
        this.quoteMarketTime = quoteMarketTime;
    }

    public OffsetDateTime getTriggeredAt() {
        return triggeredAt;
    }

    public void setTriggeredAt(OffsetDateTime triggeredAt) {
        this.triggeredAt = triggeredAt;
    }

    public String getDeduplicationKey() {
        return deduplicationKey;
    }

    public void setDeduplicationKey(String deduplicationKey) {
        this.deduplicationKey = deduplicationKey;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
}
