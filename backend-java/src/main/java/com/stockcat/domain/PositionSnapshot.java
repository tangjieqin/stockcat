package com.stockcat.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 每日持仓快照（对应 docs/DATABASE.md position_snapshot，P1.5）。
 *
 * <p>快照不是事实来源：事实是 trade_record + 收盘价。快照只是"某天收盘时按流水重放的结果"，
 * 因此允许整段删除重建，不做增量修补。
 */
public class PositionSnapshot {

    private Long id;
    private LocalDate tradeDate;
    private Long instrumentId;
    private BigDecimal quantity;
    private BigDecimal avgCost;
    /** 持仓成本 = quantity × avgCost */
    private BigDecimal costValue;
    private BigDecimal closePrice;
    private BigDecimal marketValue;
    private BigDecimal unrealizedPnl;
    /** 截至当日累计已实现盈亏 */
    private BigDecimal realizedPnlCum;
    private String priceAdjust;
    private String sourceCode;
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public Long getInstrumentId() {
        return instrumentId;
    }

    public void setInstrumentId(Long instrumentId) {
        this.instrumentId = instrumentId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getAvgCost() {
        return avgCost;
    }

    public void setAvgCost(BigDecimal avgCost) {
        this.avgCost = avgCost;
    }

    public BigDecimal getCostValue() {
        return costValue;
    }

    public void setCostValue(BigDecimal costValue) {
        this.costValue = costValue;
    }

    public BigDecimal getClosePrice() {
        return closePrice;
    }

    public void setClosePrice(BigDecimal closePrice) {
        this.closePrice = closePrice;
    }

    public BigDecimal getMarketValue() {
        return marketValue;
    }

    public void setMarketValue(BigDecimal marketValue) {
        this.marketValue = marketValue;
    }

    public BigDecimal getUnrealizedPnl() {
        return unrealizedPnl;
    }

    public void setUnrealizedPnl(BigDecimal unrealizedPnl) {
        this.unrealizedPnl = unrealizedPnl;
    }

    public BigDecimal getRealizedPnlCum() {
        return realizedPnlCum;
    }

    public void setRealizedPnlCum(BigDecimal realizedPnlCum) {
        this.realizedPnlCum = realizedPnlCum;
    }

    public String getPriceAdjust() {
        return priceAdjust;
    }

    public void setPriceAdjust(String priceAdjust) {
        this.priceAdjust = priceAdjust;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
