package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.AlertEvent;
import com.stockcat.domain.DiaryNode;
import com.stockcat.domain.MarketRecap;
import com.stockcat.mapper.AlertEventMapper;
import com.stockcat.web.dto.AgentToolDtos.*;
import com.stockcat.web.dto.AgentToolResponse;
import com.stockcat.web.dto.InstrumentResponse;
import com.stockcat.web.dto.LibraryDtos;
import com.stockcat.web.dto.PageResponse;
import com.stockcat.web.dto.PositionResponse;
import com.stockcat.web.dto.QuoteResponse;
import com.stockcat.web.dto.WatchlistItemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2.1 只读工具：缺数据要进 missing 而不是补 0，AI 草稿不能当历史依据，limit 必须夹紧。
 */
class AgentToolServiceTest {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-10-27T06:30:00Z");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-10-27T06:31:00Z");

    private WatchlistService watchlistService;
    private PositionService positionService;
    private AlertEventMapper alertEventMapper;
    private IndexService indexService;
    private RecapService recapService;
    private DiaryService diaryService;
    private LibraryService libraryService;
    private AgentToolService service;

    @BeforeEach
    void setUp() {
        watchlistService = mock(WatchlistService.class);
        positionService = mock(PositionService.class);
        alertEventMapper = mock(AlertEventMapper.class);
        indexService = mock(IndexService.class);
        recapService = mock(RecapService.class);
        diaryService = mock(DiaryService.class);
        libraryService = mock(LibraryService.class);
        service = new AgentToolService(watchlistService, positionService, alertEventMapper,
                indexService, recapService, diaryService, libraryService);
    }

    // ------------------------------------------------------------------ 自选行情

    @Test
    void watchlistQuotesKeepsMissingQuoteNullAndReportsIt() {
        when(watchlistService.list()).thenReturn(List.of(
                watchItem(1L, "600519", "贵州茅台", null, quote(1700.0, 1.5, "VALID", T1)),
                watchItem(2L, "510300", "沪深300ETF", null, null)));

        AgentToolResponse<WatchlistQuotes> resp = service.watchlistQuotes();

        assertThat(resp.tool()).isEqualTo("watchlist-quotes");
        assertThat(resp.data().count()).isEqualTo(2);
        assertThat(resp.dataAsOf()).isEqualTo(T1);

        WatchlistQuote missing = resp.data().items().get(1);
        // 没有快照就是 null，不能是 0——0 会让模型写出"价格为零"
        assertThat(missing.lastPrice()).isNull();
        assertThat(missing.changePercent()).isNull();
        assertThat(missing.dataStatus()).isEqualTo("NO_DATA");
        assertThat(resp.missing()).anyMatch(m -> m.contains("510300") && m.contains("无行情快照"));
    }

    @Test
    void watchlistQuotesLabelsDefaultGroupAndFlagsStale() {
        when(watchlistService.list()).thenReturn(List.of(
                watchItem(1L, "600519", "贵州茅台", null, quote(1700.0, 1.5, "STALE", T1)),
                watchItem(2L, "000001", "平安银行", "我的组合1", quote(11.2, -0.8, "VALID", T2))));

        AgentToolResponse<WatchlistQuotes> resp = service.watchlistQuotes();

        assertThat(resp.data().items().get(0).groupName()).isEqualTo("自选");
        assertThat(resp.data().items().get(1).groupName()).isEqualTo("我的组合1");
        assertThat(resp.missing()).anyMatch(m -> m.contains("600519") && m.contains("STALE"));
        assertThat(resp.dataAsOf()).isEqualTo(T2); // 取最晚的采集时间
    }

    // ------------------------------------------------------------------ 持仓

    @Test
    void positionsAggregateOnlyPricedAndFlagIncomplete() {
        when(positionService.list()).thenReturn(List.of(
                position(1L, "600519", "贵州茅台", "100", "10", "12", "1200", "200", "50", T1, "VALID"),
                position(2L, "510300", "沪深300ETF", "200", "4", null, null, null, "80", null, "STALE")));

        AgentToolResponse<Positions> resp = service.positions();

        Positions data = resp.data();
        assertThat(data.count()).isEqualTo(2);
        assertThat(data.totalMarketValue()).isEqualByComparingTo("1200"); // 缺价的没被当 0 相加
        assertThat(data.totalUnrealizedPnl()).isEqualByComparingTo("200");
        assertThat(data.totalRealizedPnl()).isEqualByComparingTo("130");
        assertThat(data.pricedCount()).isEqualTo(1);
        assertThat(data.priceComplete()).isFalse();
        assertThat(data.note()).contains("priceComplete=false");
        assertThat(resp.missing()).anyMatch(m -> m.contains("510300") && m.contains("缺有效价格"));
        assertThat(data.items().get(1).marketValue()).isNull();
    }

    @Test
    void positionsAllPricedIsCompleteWithNoMissing() {
        when(positionService.list()).thenReturn(List.of(
                position(1L, "600519", "贵州茅台", "100", "10", "12", "1200", "200", "0", T1, "VALID")));

        AgentToolResponse<Positions> resp = service.positions();

        assertThat(resp.data().priceComplete()).isTrue();
        assertThat(resp.data().pricedCount()).isEqualTo(1);
        assertThat(resp.missing()).isEmpty();
    }

    // ------------------------------------------------------------------ 告警

    @Test
    void alertEventsClampsLimitAndUsesTriggeredAtAsDataAsOf() {
        AlertEvent event = alertEvent(9L, "600519", "涨幅超 5%", "PRICE_CHANGE_PERCENT", T1);
        when(alertEventMapper.selectPage(null, null, null, null, 0, 50)).thenReturn(List.of(event));

        AgentToolResponse<AlertEvents> resp = service.alertEvents(999);

        verify(alertEventMapper).selectPage(null, null, null, null, 0, 50);
        assertThat(resp.dataAsOf()).isEqualTo(T1);
        assertThat(resp.data().count()).isEqualTo(1);
        assertThat(resp.data().items().get(0).symbol()).isEqualTo("600519");
        assertThat(resp.missing()).isEmpty();
    }

    @Test
    void alertEventsFallsBackToDefaultLimitWhenNonPositive() {
        when(alertEventMapper.selectPage(null, null, null, null, 0, 10)).thenReturn(List.of());

        AgentToolResponse<AlertEvents> resp = service.alertEvents(0);

        verify(alertEventMapper).selectPage(null, null, null, null, 0, 10);
        // 没有告警是正常状态，不是"数据缺失"
        assertThat(resp.missing()).isEmpty();
        assertThat(resp.data().count()).isZero();
        assertThat(resp.dataAsOf()).isNull();
    }

    // ------------------------------------------------------------------ 市场概况

    @Test
    void marketBriefCountsOnlyValidQuotes() {
        when(indexService.listLatest()).thenReturn(List.of(
                new IndexService.IndexQuote("000001", "上证指数", "中国", 3300.5, 0.42, T1, T1, "eastmoney")));
        when(watchlistService.list()).thenReturn(List.of(
                watchItem(1L, "600519", "贵州茅台", null, quote(1700.0, 2.0, "VALID", T1)),
                watchItem(2L, "000001", "平安银行", null, quote(11.2, -1.0, "VALID", T1)),
                watchItem(3L, "600000", "浦发银行", null, quote(7.0, 0.0, "VALID", T1)),
                // STALE 的 +5% 是过时数据，不能算进"今天涨"
                watchItem(4L, "510300", "沪深300ETF", null, quote(4.1, 5.0, "STALE", T1)),
                watchItem(5L, "159915", "创业板ETF", null, null)));

        AgentToolResponse<MarketBrief> resp = service.marketBrief(LocalDate.now(CN));

        Breadth breadth = resp.data().watchlistBreadth();
        assertThat(breadth.total()).isEqualTo(5);
        assertThat(breadth.up()).isEqualTo(1);
        assertThat(breadth.down()).isEqualTo(1);
        assertThat(breadth.flat()).isEqualTo(1);
        assertThat(breadth.noData()).isEqualTo(2);
        // (2 - 1 + 0) / 3 = 0.3333
        assertThat(breadth.avgChangePercent()).isEqualTo(0.3333);
        assertThat(resp.data().indices()).hasSize(1);
        // 指数未采集时，dataAsOf 仍应反映自选行情的采集时间，而不是 null
        assertThat(resp.dataAsOf()).isEqualTo(T1);
        assertThat(resp.missing()).isEmpty();
    }

    @Test
    void marketBriefDataAsOfFallsBackToWatchlistWhenIndicesMissing() {
        when(indexService.listLatest()).thenReturn(List.of());
        when(watchlistService.list()).thenReturn(List.of(
                watchItem(1L, "600519", "贵州茅台", null, quote(1700.0, 1.0, "VALID", T2))));

        AgentToolResponse<MarketBrief> resp = service.marketBrief(LocalDate.now(CN));

        assertThat(resp.dataAsOf()).isEqualTo(T2);
        assertThat(resp.missing()).anyMatch(m -> m.contains("指数行情尚未采集"));
    }

    @Test
    void marketBriefWithPastDateSaysNoHistoryBackfill() {
        when(indexService.listLatest()).thenReturn(List.of());
        when(watchlistService.list()).thenReturn(List.of());

        LocalDate past = LocalDate.now(CN).minusDays(10);
        AgentToolResponse<MarketBrief> resp = service.marketBrief(past);

        assertThat(resp.data().date()).isEqualTo(past);
        assertThat(resp.data().watchlistBreadth().avgChangePercent()).isNull();
        assertThat(resp.missing()).anyMatch(m -> m.contains("无法回补"));
        assertThat(resp.missing()).anyMatch(m -> m.contains("指数行情尚未采集"));
        assertThat(resp.missing()).anyMatch(m -> m.contains("自选列表为空"));
    }

    // ------------------------------------------------------------------ 历史复盘

    @Test
    void recapsExcludeDraftsAndTruncatePreview() {
        String longContent = "复盘正文".repeat(100);
        MarketRecap manual = recap(1L, "2026-10-24", "10月24日复盘", "MANUAL", longContent, T1);
        MarketRecap draft = recap(2L, "2026-10-25", "10月25日草稿", "AI_DRAFT", "草稿内容", T2);
        when(recapService.list(null, null, null, null, 0, 5))
                .thenReturn(new PageResponse<>(List.of(manual, draft), 0, 5, 2));

        AgentToolResponse<Recaps> resp = service.recaps(null, null, 0);

        assertThat(resp.data().count()).isEqualTo(1);
        assertThat(resp.data().items().get(0).recapId()).isEqualTo(1L);
        assertThat(resp.data().items().get(0).contentPreview()).hasSize(AgentToolService.PREVIEW_CHARS + 1)
                .endsWith("…");
        // AI 草稿不能当历史依据喂回模型
        assertThat(resp.missing()).anyMatch(m -> m.contains("AI 草稿") && m.contains("1 篇"));
    }

    @Test
    void recapsClampsLimitToMaxAndReportsEmptyRange() {
        when(recapService.list(null, null, null, null, 0, 20)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0));

        AgentToolResponse<Recaps> resp = service.recaps(null, null, 500);

        verify(recapService).list(null, null, null, null, 0, 20);
        assertThat(resp.data().items()).isEmpty();
        assertThat(resp.missing()).anyMatch(m -> m.contains("没有已确认"));
    }

    // ------------------------------------------------------------------ 日记检索

    @Test
    void diarySearchRejectsBlankKeyword() {
        assertThatThrownBy(() -> service.diarySearch("  ", 5))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("keyword 必填");
    }

    @Test
    void diarySearchReturnsPathAndPreview() {
        when(diaryService.search(eq("白酒"), anyInt()))
                .thenReturn(List.of(new DiaryService.DiarySearchHit(7L, "白酒周期", List.of("投资", "复盘"))));
        DiaryNode node = new DiaryNode();
        node.setId(7L);
        node.setContent("白酒库存周期见底，观察批价…");
        when(diaryService.get(7L)).thenReturn(node);

        AgentToolResponse<DiarySearch> resp = service.diarySearch("白酒", 5);

        DiaryHit hit = resp.data().hits().get(0);
        assertThat(hit.path()).isEqualTo("投资 / 复盘");
        assertThat(hit.contentPreview()).startsWith("白酒库存周期见底");
        assertThat(resp.dataAsOf()).isNull(); // 日记没有行情时间语义，不能编一个
    }

    @Test
    void diarySearchNoHitIsReportedAsMissing() {
        when(diaryService.search(eq("锂矿"), anyInt())).thenReturn(List.of());

        AgentToolResponse<DiarySearch> resp = service.diarySearch("锂矿", 5);

        assertThat(resp.data().hits()).isEmpty();
        assertThat(resp.missing()).anyMatch(m -> m.contains("未命中"));
    }

    // ------------------------------------------------------------------ 资料检索（P3.3）

    @Test
    void librarySearchRejectsBlankKeyword() {
        assertThatThrownBy(() -> service.librarySearch(" ", 5))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("keyword 必填");
    }

    @Test
    void librarySearchBuildsCitationFromRealSeqAndUsesImportTimeAsDataAsOf() {
        when(libraryService.search(eq("渠道库存"), anyInt(), eq(null))).thenReturn(
                new LibraryDtos.SearchResult("渠道库存", List.of("渠道库存"), List.of(
                        libraryHit(11L, 3L, "白酒渠道调研纪要", 2, "批价",
                                "主力产品一批价在国庆后回落约 30 元",
                                OffsetDateTime.parse("2026-09-25T02:00:00Z")),
                        libraryHit(9L, 3L, "白酒渠道调研纪要", 1, "渠道跟踪",
                                "渠道库存周转天数从 42 天降至 35 天",
                                OffsetDateTime.parse("2026-09-21T02:00:00Z")))));

        AgentToolResponse<LibrarySearch> resp = service.librarySearch("渠道库存", 5);

        assertThat(resp.data().count()).isEqualTo(2);
        // 引用必须是服务端按 seq 拼的：第 2 段就是第 2 段，不由模型自述
        assertThat(resp.data().hits().get(0).citation()).isEqualTo("《白酒渠道调研纪要》· 第 2 段");
        assertThat(resp.data().hits().get(1).citation()).isEqualTo("《白酒渠道调研纪要》· 第 1 段");
        assertThat(resp.data().hits().get(0).title()).isEqualTo("白酒渠道调研纪要");
        assertThat(resp.dataAsOf()).isEqualTo(OffsetDateTime.parse("2026-09-25T02:00:00Z"));
        assertThat(resp.missing()).isEmpty();
    }

    @Test
    void librarySearchNoHitSaysLibraryIsEmpty() {
        when(libraryService.search(eq("渠道库存"), anyInt(), eq(null)))
                .thenReturn(new LibraryDtos.SearchResult("渠道库存", List.of(), List.of()));
        when(libraryService.list(null, 1, 1)).thenReturn(new LibraryDtos.PageResult(0, 1, 1, List.of()));

        AgentToolResponse<LibrarySearch> resp = service.librarySearch("渠道库存", 5);

        assertThat(resp.data().hits()).isEmpty();
        assertThat(resp.missing()).anyMatch(m -> m.contains("还没有任何资料"));
    }

    @Test
    void librarySearchNoHitButLibraryNotEmptySaysNoMatch() {
        when(libraryService.search(eq("光伏"), anyInt(), eq(null)))
                .thenReturn(new LibraryDtos.SearchResult("光伏", List.of(), List.of()));
        when(libraryService.list(null, 1, 1)).thenReturn(new LibraryDtos.PageResult(12, 1, 1, List.of()));

        AgentToolResponse<LibrarySearch> resp = service.librarySearch("光伏", 5);

        assertThat(resp.missing()).anyMatch(m -> m.contains("12 篇") && m.contains("光伏"));
    }

    @Test
    void librarySearchClampsLimit() {
        when(libraryService.search(any(), anyInt(), any()))
                .thenReturn(new LibraryDtos.SearchResult("x", List.of(), List.of()));
        when(libraryService.list(null, 1, 1)).thenReturn(new LibraryDtos.PageResult(0, 1, 1, List.of()));

        service.librarySearch("x", 999);

        verify(libraryService).search("x", 20, null); // MAX_SMALL_LIMIT
    }

    // ------------------------------------------------------------------ fixtures

    private static WatchlistItemResponse watchItem(long id, String symbol, String name,
                                                   String group, QuoteResponse quote) {
        return new WatchlistItemResponse(id,
                new InstrumentResponse(id, "CN_A", symbol, name, "STOCK", "CNY"),
                null, group, quote);
    }

    private static QuoteResponse quote(Double price, Double changePercent, String status,
                                       OffsetDateTime collectedAt) {
        return new QuoteResponse(price, null, changePercent, null, null,
                collectedAt, collectedAt, status, "eastmoney");
    }

    private static PositionResponse position(long id, String symbol, String name, String qty, String avg,
                                             String lastPrice, String marketValue, String unrealized,
                                             String realized, OffsetDateTime priceAsOf, String status) {
        return new PositionResponse(id, symbol, name,
                new BigDecimal(qty), new BigDecimal(avg),
                lastPrice == null ? null : new BigDecimal(lastPrice),
                marketValue == null ? null : new BigDecimal(marketValue),
                unrealized == null ? null : new BigDecimal(unrealized),
                new BigDecimal(realized), priceAsOf, status);
    }

    private static AlertEvent alertEvent(long id, String symbol, String ruleName, String ruleType,
                                         OffsetDateTime triggeredAt) {
        AlertEvent event = new AlertEvent();
        event.setId(id);
        event.setSymbol(symbol);
        event.setRuleNameSnapshot(ruleName);
        event.setRuleTypeSnapshot(ruleType);
        event.setObservedPrice(1700.0);
        event.setObservedChangePercent(5.1);
        event.setObservedDetail("{\"changePercent\":5.1}");
        event.setQuoteMarketTime(triggeredAt);
        event.setTriggeredAt(triggeredAt);
        return event;
    }

    private static MarketRecap recap(long id, String date, String title, String status,
                                     String content, OffsetDateTime updatedAt) {
        MarketRecap r = new MarketRecap();
        r.setId(id);
        r.setRecapDate(LocalDate.parse(date));
        r.setTitle(title);
        r.setStatus(status);
        r.setMarketSummary("摘要");
        r.setSectorNotes("板块");
        r.setContent(content);
        r.setUpdatedAt(updatedAt);
        return r;
    }

    private static LibraryDtos.SearchHit libraryHit(long chunkId, long documentId, String title,
                                                    int seq, String heading, String snippet,
                                                    OffsetDateTime importedAt) {
        return new LibraryDtos.SearchHit(chunkId, documentId, title, seq, heading, snippet,
                0, snippet.length(), "PASTE", null, "某券商",
                LocalDate.parse("2026-09-20"), importedAt, 6, List.of("渠道库存"), false);
    }
}
