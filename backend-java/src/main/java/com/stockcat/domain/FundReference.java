package com.stockcat.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 基金参考数据（对应 docs/DATABASE.md §3.5 fund_reference 表，P1.4）。
 *
 * <p>一行是一个基金在一个交易日的观测：盘中写 iopv/price，晚间补写 nav/nav_date。
 * 溢价率不落库——它依赖"当下价格"，存下来就会过期。
 */
public class FundReference {

    private Long id;
    private Long instrumentId;
    private LocalDate tradeDate;
    /** 单位净值（元） */
    private Double nav;
    /** 净值所属日期，可能早于 tradeDate */
    private LocalDate navDate;
    /** 参考净值，仅 ETF 有 */
    private Double iopv;
    private OffsetDateTime iopvTime;
    /** 采集当时的价格，用于事后核对溢价 */
    private Double price;
    /** 价格/IOPV 来源（tencent-qt） */
    private String marketSourceCode;
    /** 净值来源（eastmoney-f10-lsjz） */
    private String navSourceCode;
    private OffsetDateTime fetchedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getInstrumentId() {
        return instrumentId;
    }

    public void setInstrumentId(Long instrumentId) {
        this.instrumentId = instrumentId;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public Double getNav() {
        return nav;
    }

    public void setNav(Double nav) {
        this.nav = nav;
    }

    public LocalDate getNavDate() {
        return navDate;
    }

    public void setNavDate(LocalDate navDate) {
        this.navDate = navDate;
    }

    public Double getIopv() {
        return iopv;
    }

    public void setIopv(Double iopv) {
        this.iopv = iopv;
    }

    public OffsetDateTime getIopvTime() {
        return iopvTime;
    }

    public void setIopvTime(OffsetDateTime iopvTime) {
        this.iopvTime = iopvTime;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public String getMarketSourceCode() {
        return marketSourceCode;
    }

    public void setMarketSourceCode(String marketSourceCode) {
        this.marketSourceCode = marketSourceCode;
    }

    public String getNavSourceCode() {
        return navSourceCode;
    }

    public void setNavSourceCode(String navSourceCode) {
        this.navSourceCode = navSourceCode;
    }

    public OffsetDateTime getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(OffsetDateTime fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
