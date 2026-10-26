package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.FundReference;
import com.stockcat.domain.Instrument;
import com.stockcat.fund.FundNavClient;
import com.stockcat.fund.FundNavFetch;
import com.stockcat.fund.FundNavPoint;
import com.stockcat.mapper.FundReferenceMapper;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.quote.FundQuote;
import com.stockcat.quote.FundSourceException;
import com.stockcat.quote.TencentFundClient;
import com.stockcat.web.dto.FundPremiumResponse;
import com.stockcat.web.dto.FundReferenceResponse;
import com.stockcat.web.dto.FundRefreshResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基金预览与溢价（P1.4）。
 *
 * <p>重点不是"能算出来"，而是**用哪个基准算、有没有标注滞后**：
 * ETF 的 IOPV 与 LOF 的 T-1 净值会给出数值接近但含义完全不同的溢价率。
 */
class FundReferenceServiceTest {

    private FundReferenceMapper fundMapper;
    private InstrumentMapper instrumentMapper;
    private TencentFundClient tencentClient;
    private FundNavClient navClient;
    private FundReferenceService service;

    @BeforeEach
    void setUp() {
        fundMapper = Mockito.mock(FundReferenceMapper.class);
        instrumentMapper = Mockito.mock(InstrumentMapper.class);
        tencentClient = Mockito.mock(TencentFundClient.class);
        navClient = Mockito.mock(FundNavClient.class);
        service = new FundReferenceService(fundMapper, instrumentMapper, tencentClient, navClient, 30);
    }

    private static Instrument fund(long id, String symbol, String type) {
        Instrument instrument = new Instrument();
        instrument.setId(id);
        instrument.setSymbol(symbol);
        instrument.setName(symbol);
        instrument.setInstrumentType(type);
        instrument.setMarket("CN_A");
        instrument.setCurrency("CNY");
        return instrument;
    }

    private static FundReference row(String tradeDate, Double price, Double iopv, String iopvTime,
                                     Double nav, String navDate) {
        FundReference row = new FundReference();
        row.setInstrumentId(2L);
        row.setTradeDate(LocalDate.parse(tradeDate));
        row.setPrice(price);
        row.setIopv(iopv);
        row.setIopvTime(iopvTime == null ? null : OffsetDateTime.parse(iopvTime));
        row.setNav(nav);
        row.setNavDate(navDate == null ? null : LocalDate.parse(navDate));
        row.setMarketSourceCode("tencent-qt");
        row.setFetchedAt(OffsetDateTime.parse("2026-10-26T14:59:01+08:00"));
        return row;
    }

    // ------------------------------------------------------------ 溢价口径

    @Test
    void etfUsesIopvAsBasis() {
        Instrument etf = fund(2L, "510300", "ETF");
        FundReference latest = row("2026-10-26", 4.179, 4.1782, "2026-10-26T14:59:00+08:00", 4.1811, "2026-10-23");

        FundPremiumResponse premium = FundReferenceService.premium(etf, latest, latest);

        assertEquals("IOPV", premium.premiumBasis());
        // (4.179 - 4.1782) / 4.1782 × 100 = 0.019146… → 0.0191
        assertEquals(0.0191, premium.premiumRate(), 1e-9);
        assertEquals("2026-10-26T14:59+08:00", premium.premiumAsOf());
        assertEquals(3L, premium.navStaleDays(), "净值是 10-23 的，与行情日差 3 个自然日");
        // 净值仍一并返回，前端可以同时显示"参考净值"和"昨日净值"
        assertEquals(4.1811, premium.nav(), 1e-9);
        assertEquals(LocalDate.parse("2026-10-23"), premium.navDate());
    }

    @Test
    void lofUsesDatedNavAndMarksItStale() {
        Instrument lof = fund(3L, "161725", "LOF");
        FundReference latest = row("2026-10-26", 0.544, null, null, 0.5345, "2026-10-23");

        FundPremiumResponse premium = FundReferenceService.premium(lof, latest, latest);

        assertEquals("NAV", premium.premiumBasis(), "LOF 没有 IOPV，只能用净值，且必须标明");
        assertEquals("2026-10-23", premium.premiumAsOf());
        assertEquals(3L, premium.navStaleDays());
        // (0.544 - 0.5345) / 0.5345 × 100 = 1.77736… → 1.7774
        assertEquals(1.7774, premium.premiumRate(), 1e-9);
        assertNull(premium.iopv());
    }

    @Test
    void intradayRowWithoutNavFallsBackToEarlierDatedNav() {
        Instrument lof = fund(3L, "161725", "LOF");
        FundReference today = row("2026-10-26", 0.544, null, null, null, null);
        FundReference yesterday = row("2026-10-23", 0.536, null, null, 0.5345, "2026-10-23");

        FundPremiumResponse premium = FundReferenceService.premium(lof, today, yesterday);

        assertEquals("NAV", premium.premiumBasis());
        assertEquals(LocalDate.parse("2026-10-23"), premium.navDate());
        assertEquals(3L, premium.navStaleDays());
        assertEquals(1.7774, premium.premiumRate(), 1e-9);
    }

    @Test
    void withoutBasisPremiumIsNullNotZero() {
        Instrument etf = fund(2L, "510300", "ETF");
        FundReference latest = row("2026-10-26", 4.179, null, null, null, null);

        FundPremiumResponse premium = FundReferenceService.premium(etf, latest, null);

        assertNull(premium.premiumRate(), "没有基准就必须留空，不能给 0 让人以为平价");
        assertNull(premium.premiumBasis());
        assertNull(premium.premiumAsOf());
        assertNull(premium.navStaleDays());
        assertEquals(4.179, premium.price(), 1e-9);
    }

    @Test
    void undatedNavIsDroppedInsteadOfShownWithoutDate() {
        Instrument lof = fund(3L, "161725", "LOF");
        // 有净值但没有净值日期：既不能算溢价，也不能只显示数字（会被误读成"今天的净值"）
        FundReference latest = row("2026-10-26", 0.544, null, null, 0.5345, null);

        FundPremiumResponse premium = FundReferenceService.premium(lof, latest, null);

        assertNull(premium.premiumRate());
        assertNull(premium.nav(), "无法标注日期的净值整条不展示，而不是放一个没有日期的数字上去");
        assertNull(premium.navDate());
    }

    @Test
    void percentRoundsToFourDecimalsAndRejectsBadBasis() {
        assertEquals(0.0191, FundReferenceService.percent(4.179, 4.1782), 1e-9);
        assertEquals(-0.0502, FundReferenceService.percent(4.179, 4.1811), 1e-9);
        assertNull(FundReferenceService.percent(4.179, 0.0), "分母为 0 无定义");
        assertNull(FundReferenceService.percent(null, 4.0));
    }

    // ------------------------------------------------------------ 查询

    @Test
    void stockQueryIsNotApplicableNotError() {
        Mockito.when(instrumentMapper.findById(1L)).thenReturn(fund(1L, "600519", "STOCK"));

        FundReferenceResponse resp = service.query(1L, 30);

        assertFalse(resp.applicable());
        assertNotNull(resp.reason());
        assertTrue(resp.history().isEmpty());
        assertNull(resp.latest());
        Mockito.verifyNoInteractions(fundMapper);
    }

    @Test
    void unknownInstrumentIs404() {
        Mockito.when(instrumentMapper.findById(99L)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> service.query(99L, 30));

        assertEquals("NOT_FOUND", ex.getErrorCode().code());
    }

    @Test
    void emptyRowsExplainWhyInsteadOfEmptyChart() {
        Mockito.when(instrumentMapper.findById(2L)).thenReturn(fund(2L, "510300", "ETF"));
        Mockito.when(fundMapper.selectHistory(2L, 30)).thenReturn(List.of());

        FundReferenceResponse resp = service.query(2L, 30);

        assertTrue(resp.applicable());
        assertNull(resp.latest());
        assertTrue(resp.history().isEmpty());
        assertTrue(resp.reason().contains("尚未同步"));
    }

    @Test
    void queryReturnsLatestAndPerRowHistory() {
        Instrument etf = fund(2L, "510300", "ETF");
        Mockito.when(instrumentMapper.findById(2L)).thenReturn(etf);
        FundReference today = row("2026-10-26", 4.179, 4.1782, "2026-10-26T14:59:00+08:00", 4.1811, "2026-10-23");
        FundReference yesterday = row("2026-10-23", 4.19, 4.20, "2026-10-23T14:59:00+08:00", 4.1811, "2026-10-23");
        Mockito.when(fundMapper.selectHistory(2L, 30)).thenReturn(List.of(today, yesterday));
        Mockito.when(fundMapper.selectLatestDatedNavByInstrumentIds(List.of(2L))).thenReturn(List.of(today));

        FundReferenceResponse resp = service.query(2L, 30);

        assertTrue(resp.applicable());
        assertNull(resp.reason());
        assertEquals(2, resp.history().size());
        assertEquals(LocalDate.parse("2026-10-26"), resp.latest().tradeDate());
        // 历史每行用自己当天的 IOPV，不会拿今天的净值去算昨天的溢价
        assertEquals("IOPV", resp.history().get(1).premiumBasis());
        assertEquals(-0.2381, resp.history().get(1).premiumRate(), 1e-9);
    }

    // ------------------------------------------------------------ 写入

    @Test
    void refreshRejectsStock() {
        Mockito.when(instrumentMapper.findById(1L)).thenReturn(fund(1L, "600519", "STOCK"));

        ApiException ex = assertThrows(ApiException.class, () -> service.refresh(1L));

        assertEquals("VALIDATION_ERROR", ex.getErrorCode().code());
        Mockito.verifyNoInteractions(tencentClient, navClient);
    }

    @Test
    void refreshWritesMarketDataAndNav() {
        Instrument etf = fund(2L, "510300", "ETF");
        Mockito.when(instrumentMapper.findById(2L)).thenReturn(etf);
        Mockito.when(tencentClient.fetch(List.of("510300"))).thenReturn(List.of(
                new FundQuote("510300", "沪深300ETF", "ETF", 4.179, 4.1782, 4.1797,
                        OffsetDateTime.parse("2026-10-26T15:00:03+08:00"))));
        Mockito.when(navClient.fetch("510300", 30)).thenReturn(new FundNavFetch("510300", 30,
                OffsetDateTime.parse("2026-10-26T14:30:00Z"), 3517, 0,
                List.of(new FundNavPoint(LocalDate.parse("2026-10-26"), 4.1797, 1.8769, -0.03))));

        FundRefreshResponse resp = service.refresh(2L);

        assertEquals("SUCCESS", resp.status());
        assertTrue(resp.marketUpdated());
        assertEquals(1, resp.navWritten());
        assertNull(resp.code());

        ArgumentCaptor<FundReference> marketCaptor = ArgumentCaptor.forClass(FundReference.class);
        Mockito.verify(fundMapper).upsertMarketData(marketCaptor.capture());
        FundReference market = marketCaptor.getValue();
        assertEquals(LocalDate.parse("2026-10-26"), market.getTradeDate());
        assertEquals(4.179, market.getPrice(), 1e-9);
        assertEquals(4.1782, market.getIopv(), 1e-9);
        assertNull(market.getNav(), "净值只由净值同步写入，行情行不掺和");

        ArgumentCaptor<FundReference> navCaptor = ArgumentCaptor.forClass(FundReference.class);
        Mockito.verify(fundMapper).upsertNav(navCaptor.capture());
        FundReference nav = navCaptor.getValue();
        assertEquals(LocalDate.parse("2026-10-26"), nav.getNavDate());
        assertEquals(4.1797, nav.getNav(), 1e-9);
    }

    @Test
    void lofMarketRowHasNoIopvButStillWrites() {
        Instrument lof = fund(3L, "161725", "LOF");
        Mockito.when(instrumentMapper.findById(3L)).thenReturn(lof);
        Mockito.when(tencentClient.fetch(List.of("161725"))).thenReturn(List.of(
                new FundQuote("161725", "白酒基金LOF", "LOF", 0.544, null, 0.5459,
                        OffsetDateTime.parse("2026-10-26T15:00:03+08:00"))));
        Mockito.when(navClient.fetch("161725", 30)).thenReturn(new FundNavFetch("161725", 30,
                OffsetDateTime.parse("2026-10-26T14:30:00Z"), 2777, 0,
                List.of(new FundNavPoint(LocalDate.parse("2026-10-26"), 0.5459, 2.2620, 2.13))));

        service.refresh(3L);

        ArgumentCaptor<FundReference> marketCaptor = ArgumentCaptor.forClass(FundReference.class);
        Mockito.verify(fundMapper).upsertMarketData(marketCaptor.capture());
        assertNull(marketCaptor.getValue().getIopv());
        assertNull(marketCaptor.getValue().getIopvTime(), "没有 IOPV 就不要写一个孤零零的 IOPV 时间");
        assertEquals(0.544, marketCaptor.getValue().getPrice(), 1e-9);
    }

    @Test
    void refreshIsPartialWhenNavSourceIsDown() {
        Instrument etf = fund(2L, "510300", "ETF");
        Mockito.when(instrumentMapper.findById(2L)).thenReturn(etf);
        Mockito.when(tencentClient.fetch(List.of("510300"))).thenReturn(List.of(
                new FundQuote("510300", "沪深300ETF", "ETF", 4.179, 4.1782, 4.1797,
                        OffsetDateTime.parse("2026-10-26T15:00:03+08:00"))));
        Mockito.when(navClient.fetch("510300", 30))
                .thenThrow(new FundSourceException("DATA_SOURCE_UNAVAILABLE", "净值源未启动"));

        FundRefreshResponse resp = service.refresh(2L);

        assertEquals("PARTIAL", resp.status(), "IOPV 成功但净值失败：不能报 SUCCESS，也不能报 FAILED");
        assertTrue(resp.marketUpdated());
        assertEquals(0, resp.navWritten());
        assertEquals("DATA_SOURCE_UNAVAILABLE", resp.code());
        assertTrue(resp.message().contains("净值源未启动"));
    }

    @Test
    void marketFailureDoesNotBreakNav() {
        Instrument etf = fund(2L, "510300", "ETF");
        Mockito.when(instrumentMapper.findById(2L)).thenReturn(etf);
        Mockito.when(tencentClient.fetch(List.of("510300")))
                .thenThrow(new FundSourceException("DATA_SOURCE_UNAVAILABLE", "腾讯不可用"));
        Mockito.when(navClient.fetch("510300", 30)).thenReturn(new FundNavFetch("510300", 30, null, 3517, 0,
                List.of(new FundNavPoint(LocalDate.parse("2026-10-26"), 4.1797, 1.8769, -0.03))));

        FundRefreshResponse resp = service.refresh(2L);

        assertEquals("PARTIAL", resp.status());
        assertFalse(resp.marketUpdated());
        assertEquals(1, resp.navWritten());
    }

    @Test
    void latestPremiumsSkipStocksAndUnknownFunds() {
        Instrument etf = fund(2L, "510300", "ETF");
        Instrument stock = fund(1L, "600519", "STOCK");
        Mockito.when(fundMapper.selectLatestByInstrumentIds(List.of(2L)))
                .thenReturn(List.of(row("2026-10-26", 4.179, 4.1782, "2026-10-26T14:59:00+08:00", 4.1811, "2026-10-23")));
        Mockito.when(fundMapper.selectLatestDatedNavByInstrumentIds(List.of(2L)))
                .thenReturn(List.of(row("2026-10-26", 4.179, 4.1782, "2026-10-26T14:59:00+08:00", 4.1811, "2026-10-23")));

        Map<Long, FundPremiumResponse> result = service.latestPremiums(List.of(etf, stock));

        assertEquals(1, result.size());
        assertEquals("IOPV", result.get(2L).premiumBasis());
        assertFalse(result.containsKey(1L), "股票不出现在溢价结果里，前端显示 —");
    }
}
