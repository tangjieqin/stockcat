package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.AlertEvent;
import com.stockcat.domain.MarketRecap;
import com.stockcat.mapper.AlertEventMapper;
import com.stockcat.web.dto.AgentToolDtos.*;
import com.stockcat.web.dto.AgentToolResponse;
import com.stockcat.web.dto.LibraryDtos;
import com.stockcat.web.dto.PageResponse;
import com.stockcat.web.dto.PositionResponse;
import com.stockcat.web.dto.QuoteResponse;
import com.stockcat.web.dto.WatchlistItemResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * P2.1 Agent 受控只读工具（docs/PLAN_P2.md §2、docs/API.md §10）。
 *
 * <p><b>只读是硬约束</b>：本类只调用查询方法，不存在任何 insert/update/delete 路径。
 * Agent 拿不到写能力，就造不出"改流水/改规则/发通知"这类事故。
 *
 * <p><b>不补 0</b>：取不到的数值一律 null，并把原因写进 {@code missing}。
 * 模型看到 null 会写"数据缺失"，看到 0 会写"市值为 0"——后者是它编的。
 */
@Service
public class AgentToolService {

    /** 复盘/日记预览截断长度：工具只给"够用的上下文"，不把整篇正文塞进 prompt（token 成本）。 */
    static final int PREVIEW_CHARS = 240;

    private static final int DEFAULT_ALERT_LIMIT = 10;
    private static final int MAX_ALERT_LIMIT = 50;
    private static final int DEFAULT_RECAP_LIMIT = 5;
    private static final int DEFAULT_DIARY_LIMIT = 5;
    private static final int DEFAULT_LIBRARY_LIMIT = 5;
    private static final int MAX_SMALL_LIMIT = 20;
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    public static final String POSITIONS_NOTE =
            "市值与浮动盈亏只基于有效行情快照；priceComplete=false 时汇总值只含能取到有效价格的持仓，"
                    + "不能当作全组合市值；已实现盈亏为累计值。";
    public static final String BRIEF_NOTE =
            "指数与自选行情都是采集时点的内存快照（非交易所官方收盘价），只代表采集时刻；"
                    + "本工具不回补历史指数，date 仅表示本次复盘的归属日期。";

    private final WatchlistService watchlistService;
    private final PositionService positionService;
    private final AlertEventMapper alertEventMapper;
    private final IndexService indexService;
    private final RecapService recapService;
    private final DiaryService diaryService;
    private final LibraryService libraryService;

    public AgentToolService(WatchlistService watchlistService, PositionService positionService,
                            AlertEventMapper alertEventMapper, IndexService indexService,
                            RecapService recapService, DiaryService diaryService,
                            LibraryService libraryService) {
        this.watchlistService = watchlistService;
        this.positionService = positionService;
        this.alertEventMapper = alertEventMapper;
        this.indexService = indexService;
        this.recapService = recapService;
        this.diaryService = diaryService;
        this.libraryService = libraryService;
    }

    // ------------------------------------------------------------------ 自选行情

    /** 自选标的的最新行情。无快照的标的照样出现（quote 字段为 null），并计入 missing。 */
    public AgentToolResponse<WatchlistQuotes> watchlistQuotes() {
        List<WatchlistItemResponse> rows = watchlistService.list();
        List<WatchlistQuote> items = new ArrayList<>(rows.size());
        List<String> missing = new ArrayList<>();
        OffsetDateTime dataAsOf = null;

        for (WatchlistItemResponse row : rows) {
            var ins = row.instrument();
            QuoteResponse q = row.quote();
            String symbol = ins == null ? null : ins.symbol();
            String status = q == null ? "NO_DATA" : q.dataStatus();
            String who = label(symbol, ins == null ? null : ins.name());
            if (q == null) {
                missing.add(who + " 无行情快照");
            } else {
                if (q.collectedAt() != null && (dataAsOf == null || q.collectedAt().isAfter(dataAsOf))) {
                    dataAsOf = q.collectedAt();
                }
                if (!"VALID".equals(q.dataStatus())) {
                    missing.add(who + " 行情状态非有效（" + q.dataStatus() + "），价格不可当作当前价");
                }
            }
            items.add(new WatchlistQuote(
                    row.id(), symbol, ins == null ? null : ins.name(),
                    ins == null ? null : ins.type(),
                    row.groupName() == null ? "自选" : row.groupName(),
                    q == null ? null : q.lastPrice(),
                    q == null ? null : q.previousClose(),
                    q == null ? null : q.changePercent(),
                    q == null ? null : q.marketTime(),
                    q == null ? null : q.collectedAt(),
                    status));
        }
        return AgentToolResponse.of("watchlist-quotes", dataAsOf, new WatchlistQuotes(items.size(), items), missing);
    }

    // ------------------------------------------------------------------ 持仓

    /** 当前持仓（由交易流水重放得出），带汇总。缺价持仓不参与市值汇总，只计入 missing。 */
    public AgentToolResponse<Positions> positions() {
        List<PositionResponse> rows = positionService.list();
        List<PositionItem> items = new ArrayList<>(rows.size());
        List<String> missing = new ArrayList<>();

        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        int priced = 0;
        boolean priceComplete = true;
        OffsetDateTime dataAsOf = null;

        for (PositionResponse p : rows) {
            boolean validPrice = "VALID".equals(p.priceStatus()) && p.lastPrice() != null;
            if (validPrice && p.marketValue() != null) {
                marketValue = marketValue.add(p.marketValue());
                priced++;
                if (p.priceAsOf() != null && (dataAsOf == null || p.priceAsOf().isAfter(dataAsOf))) {
                    dataAsOf = p.priceAsOf();
                }
            } else {
                priceComplete = false;
                missing.add(label(p.symbol(), p.name()) + " 缺有效价格（priceStatus=" + p.priceStatus()
                        + "），市值未计入汇总");
            }
            if (p.unrealizedPnl() != null) {
                unrealized = unrealized.add(p.unrealizedPnl());
            }
            if (p.realizedPnl() != null) {
                realized = realized.add(p.realizedPnl());
            }
            items.add(new PositionItem(p.instrumentId(), p.symbol(), p.name(), p.quantity(), p.avgCost(),
                    p.lastPrice(), p.marketValue(), p.unrealizedPnl(), p.realizedPnl(),
                    p.priceAsOf(), p.priceStatus()));
        }
        return AgentToolResponse.of("positions", dataAsOf,
                new Positions(items.size(), marketValue, unrealized, realized, priced, priceComplete,
                        POSITIONS_NOTE, items),
                missing);
    }

    // ------------------------------------------------------------------ 告警事件

    /** 最近触发的告警事件（按触发时间倒序）。空列表是正常状态，不进 missing。 */
    public AgentToolResponse<AlertEvents> alertEvents(int limit) {
        int effective = clamp(limit <= 0 ? DEFAULT_ALERT_LIMIT : limit, 1, MAX_ALERT_LIMIT);
        List<AlertEvent> rows = alertEventMapper.selectPage(null, null, null, null, 0, effective);
        OffsetDateTime dataAsOf = null;
        List<AlertEventItem> items = new ArrayList<>(rows.size());
        for (AlertEvent e : rows) {
            if (e.getTriggeredAt() != null && (dataAsOf == null || e.getTriggeredAt().isAfter(dataAsOf))) {
                dataAsOf = e.getTriggeredAt();
            }
            items.add(new AlertEventItem(e.getId(), e.getSymbol(), e.getRuleNameSnapshot(),
                    e.getRuleTypeSnapshot(), e.getObservedPrice(), e.getObservedChangePercent(),
                    e.getObservedDetail(), e.getQuoteMarketTime(), e.getTriggeredAt()));
        }
        return AgentToolResponse.of("alert-events", dataAsOf, new AlertEvents(items.size(), items), List.of());
    }

    // ------------------------------------------------------------------ 市场概况

    /**
     * 当日市场概况：指数行情 + 自选涨跌分布。
     *
     * <p>date 只表示"这份复盘归属哪一天"，指数是内存里的最新快照，**不回补历史**；
     * 传入历史日期时必须在 missing 里说清，否则模型会把它当成当日指数写进复盘。
     */
    public AgentToolResponse<MarketBrief> marketBrief(LocalDate date) {
        LocalDate today = LocalDate.now(CN);
        LocalDate resolved = date != null ? date : today;
        List<String> missing = new ArrayList<>();

        List<IndexQuote> indices = new ArrayList<>();
        OffsetDateTime dataAsOf = null;
        for (IndexService.IndexQuote idx : indexService.listLatest()) {
            if (idx.collectedAt() != null && (dataAsOf == null || idx.collectedAt().isAfter(dataAsOf))) {
                dataAsOf = idx.collectedAt();
            }
            indices.add(new IndexQuote(idx.symbol(), idx.name(), idx.lastPrice(), idx.changePercent(),
                    idx.marketTime(), idx.collectedAt()));
        }
        if (indices.isEmpty()) {
            missing.add("指数行情尚未采集（服务刚启动或数据源不可用），不能据此描述大盘点位");
        }
        if (!resolved.equals(today)) {
            missing.add("指数与自选行情只有最新采集快照，无法回补 " + resolved + " 当日的历史值");
        }

        int total = 0, up = 0, down = 0, flat = 0, noData = 0, counted = 0;
        double sum = 0;
        for (WatchlistItemResponse row : watchlistService.list()) {
            total++;
            QuoteResponse q = row.quote();
            if (q != null && q.collectedAt() != null
                    && (dataAsOf == null || q.collectedAt().isAfter(dataAsOf))) {
                dataAsOf = q.collectedAt();
            }
            // 只有 VALID 行情参与涨跌统计：STALE 的涨跌幅是过时的，算进去会把"昨天涨"说成"今天涨"
            if (q == null || !"VALID".equals(q.dataStatus()) || q.changePercent() == null) {
                noData++;
                continue;
            }
            double pct = q.changePercent();
            sum += pct;
            counted++;
            if (pct > 0) {
                up++;
            } else if (pct < 0) {
                down++;
            } else {
                flat++;
            }
        }
        Double avg = counted == 0 ? null : BigDecimal.valueOf(sum / counted)
                .setScale(4, RoundingMode.HALF_UP).doubleValue();
        Breadth breadth = new Breadth(total, up, down, flat, noData, avg);
        if (total == 0) {
            missing.add("自选列表为空，无法给出涨跌分布");
        }
        return AgentToolResponse.of("market-brief", dataAsOf,
                new MarketBrief(resolved, indices, breadth, BRIEF_NOTE), missing);
    }

    // ------------------------------------------------------------------ 历史复盘

    /**
     * 历史复盘摘要（按日期倒序）。
     *
     * <p><b>只返回 status=MANUAL</b>：AI 草稿是 AI 自己写的东西，不能当成"历史事实"再喂回模型，
     * 否则会自我强化；被排除的草稿数写进 missing 让它可见。
     */
    public AgentToolResponse<Recaps> recaps(LocalDate from, LocalDate to, int limit) {
        int effective = clamp(limit <= 0 ? DEFAULT_RECAP_LIMIT : limit, 1, MAX_SMALL_LIMIT);
        List<String> missing = new ArrayList<>();
        PageResponse<MarketRecap> page = recapService.list(null, from, to, null, 0, effective);

        List<RecapSummary> items = new ArrayList<>();
        int drafts = 0;
        OffsetDateTime dataAsOf = null;
        for (MarketRecap r : page.items()) {
            if (!"MANUAL".equals(r.getStatus())) {
                drafts++;
                continue;
            }
            if (r.getUpdatedAt() != null && (dataAsOf == null || r.getUpdatedAt().isAfter(dataAsOf))) {
                dataAsOf = r.getUpdatedAt();
            }
            items.add(new RecapSummary(r.getId(), r.getRecapDate(), r.getTitle(), r.getStatus(),
                    preview(r.getMarketSummary()), preview(r.getSectorNotes()), preview(r.getContent()),
                    r.getUpdatedAt()));
        }
        if (drafts > 0) {
            missing.add("查询范围内有 " + drafts + " 篇 AI 草稿（status=AI_DRAFT）未纳入，草稿不作为历史依据");
        }
        if (items.isEmpty() && drafts == 0) {
            missing.add("查询范围内没有已确认（MANUAL）的复盘");
        }
        return AgentToolResponse.of("recaps", dataAsOf, new Recaps(items.size(), items), missing);
    }

    // ------------------------------------------------------------------ 日记检索

    /** 日记正文检索：命中标题、树路径与正文预览。keyword 必填——全量导出不是工具用途。 */
    public AgentToolResponse<DiarySearch> diarySearch(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            throw ApiException.validationError("keyword 必填：日记检索不接受空关键词");
        }
        String trimmed = keyword.trim();
        int effective = clamp(limit <= 0 ? DEFAULT_DIARY_LIMIT : limit, 1, MAX_SMALL_LIMIT);

        List<DiaryHit> hits = new ArrayList<>();
        for (DiaryService.DiarySearchHit hit : diaryService.search(trimmed, effective)) {
            String content = null;
            try {
                content = preview(diaryService.get(hit.id()).getContent());
            } catch (ApiException ignored) {
                // 命中后被删：不给预览，标题与路径仍然有效
            }
            hits.add(new DiaryHit(hit.id(), hit.title(), String.join(" / ", hit.path()), content));
        }
        List<String> missing = hits.isEmpty() ? List.of("未命中任何日记（关键词：" + trimmed + "）") : List.of();
        return AgentToolResponse.of("diary-search", null, new DiarySearch(trimmed, hits.size(), hits), missing);
    }

    // ------------------------------------------------------------------ 资料检索

    /**
     * 资料库片段检索（P3.3）：命中单位是**片段**，不是整篇。
     *
     * <p>两个刻意的设计：
     * <ul>
     *   <li>每条命中都带 {@code citation}（{@code 《标题》· 第 N 段}），由这里按真实的
     *       {@code seq} 拼装。模型只能引用这串现成字符，不能自述出处——否则换了检索方式，
     *       引用就和原文对不上了。</li>
     *   <li>没命中时进 {@code missing} 并区分「库里一篇都没有」与「有资料但没命中」：
     *       前者模型该直说"资料库是空的"，后者该说"没有找到相关的"；两种都不是"用模型知识补一个答案"。</li>
     * </ul>
     */
    public AgentToolResponse<LibrarySearch> librarySearch(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            throw ApiException.validationError("keyword 必填：资料检索不接受空关键词");
        }
        String trimmed = keyword.trim();
        int effective = clamp(limit <= 0 ? DEFAULT_LIBRARY_LIMIT : limit, 1, MAX_SMALL_LIMIT);

        LibraryDtos.SearchResult result = libraryService.search(trimmed, effective, null);
        List<LibraryHit> hits = new ArrayList<>(result.hits().size());
        OffsetDateTime dataAsOf = null;
        for (LibraryDtos.SearchHit hit : result.hits()) {
            hits.add(new LibraryHit(
                    hit.chunkId(), hit.documentId(), hit.documentTitle(), hit.seq(), hit.heading(),
                    hit.snippet(), hit.sourceType(), hit.publisher(), hit.publishedAt(),
                    citation(hit.documentTitle(), hit.seq())));
            if (hit.importedAt() != null && (dataAsOf == null || hit.importedAt().isAfter(dataAsOf))) {
                dataAsOf = hit.importedAt();
            }
        }

        List<String> missing = List.of();
        if (hits.isEmpty()) {
            long total = libraryService.list(null, 1, 1).total();
            missing = List.of(total == 0
                    ? "资料库里还没有任何资料，无法回答与该主题相关的问题"
                    : "资料库共有 " + total + " 篇资料，但没有命中「" + trimmed + "」的片段");
        }
        return AgentToolResponse.of("library-search", dataAsOf,
                new LibrarySearch(trimmed, hits.size(), hits), missing);
    }

    /** 引用格式只在这一处定义：前端展示、模型 prompt、校验白名单都用它，避免三处各写一份。 */
    static String citation(String title, Integer seq) {
        return "《" + (title == null ? "无标题" : title) + "》· 第 " + (seq == null ? 1 : seq) + " 段";
    }

    // ------------------------------------------------------------------ 内部

    private static String label(String symbol, String name) {
        String s = symbol == null ? "未知标的" : symbol;
        return name == null || name.isBlank() ? s : s + " " + name;
    }

    static String preview(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.length() <= PREVIEW_CHARS ? trimmed : trimmed.substring(0, PREVIEW_CHARS) + "…";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
