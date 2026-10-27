package com.stockcat.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * P2.1 Agent 只读工具的数据载荷（docs/PLAN_P2.md §2）。
 *
 * <p>设计约定：
 * <ul>
 *   <li>字段名就是模型看到的字段名，必须自解释（不出现 {@code qty}/{@code pnl} 这类缩写）；</li>
 *   <li>取不到值一律 {@code null}，<b>不补 0</b>——0 是"确定为零"，null 是"不知道"，两者语义完全不同；</li>
 *   <li>缺失原因由外层 {@link AgentToolResponse#missing()} 汇总，模型据此写"数据缺失"而不是编数。</li>
 * </ul>
 */
public class AgentToolDtos {

    /** 自选行情条目。dataStatus：VALID / STALE / NO_DATA（见 docs/API.md §2）。 */
    public record WatchlistQuote(
            Long instrumentId,
            String symbol,
            String name,
            String type,
            String groupName,
            Double lastPrice,
            Double previousClose,
            Double changePercent,
            OffsetDateTime marketTime,
            OffsetDateTime collectedAt,
            String dataStatus
    ) {
    }

    public record WatchlistQuotes(int count, List<WatchlistQuote> items) {
    }

    /** 持仓条目：priceStatus 非 VALID 时 marketValue/unrealizedPnl 为 null。 */
    public record PositionItem(
            Long instrumentId,
            String symbol,
            String name,
            BigDecimal quantity,
            BigDecimal avgCost,
            BigDecimal lastPrice,
            BigDecimal marketValue,
            BigDecimal unrealizedPnl,
            BigDecimal realizedPnl,
            OffsetDateTime priceAsOf,
            String priceStatus
    ) {
    }

    /**
     * 持仓汇总。{@code priceComplete=false} 表示有持仓缺有效行情：
     * 此时 {@code totalMarketValue}/{@code totalUnrealizedPnl} 只是"能取到价的部分"，
     * 不能当作全组合市值使用（与 {@code PortfolioService} 的 complete 语义一致）。
     */
    public record Positions(
            int count,
            BigDecimal totalMarketValue,
            BigDecimal totalUnrealizedPnl,
            BigDecimal totalRealizedPnl,
            int pricedCount,
            boolean priceComplete,
            String note,
            List<PositionItem> items
    ) {
    }

    public record AlertEventItem(
            Long eventId,
            String symbol,
            String ruleName,
            String ruleType,
            Double observedPrice,
            Double observedChangePercent,
            String observedDetail,
            OffsetDateTime quoteMarketTime,
            OffsetDateTime triggeredAt
    ) {
    }

    public record AlertEvents(int count, List<AlertEventItem> items) {
    }

    public record IndexQuote(
            String symbol,
            String name,
            Double lastPrice,
            Double changePercent,
            OffsetDateTime marketTime,
            OffsetDateTime collectedAt
    ) {
    }

    /**
     * 自选涨跌分布。{@code noData} 含无快照与行情状态非 VALID 的只数；
     * {@code avgChangePercent} 只对有效行情求均值，全是 noData 时为 null（不是 0）。
     */
    public record Breadth(int total, int up, int down, int flat, int noData, Double avgChangePercent) {
    }

    public record MarketBrief(
            LocalDate date,
            List<IndexQuote> indices,
            Breadth watchlistBreadth,
            String note
    ) {
    }

    /** 历史复盘摘要：正文截断给前 {@code PREVIEW_CHARS} 字，避免把整篇塞进 prompt。 */
    public record RecapSummary(
            Long recapId,
            LocalDate recapDate,
            String title,
            String status,
            String marketSummaryPreview,
            String sectorNotesPreview,
            String contentPreview,
            OffsetDateTime updatedAt
    ) {
    }

    public record Recaps(int count, List<RecapSummary> items) {
    }

    public record DiaryHit(Long nodeId, String title, String path, String contentPreview) {
    }

    public record DiarySearch(String keyword, int count, List<DiaryHit> hits) {
    }
}
