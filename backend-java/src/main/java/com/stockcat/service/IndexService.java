package com.stockcat.service;

import com.stockcat.quote.EastMoneyQuoteClient;
import com.stockcat.quote.StandardQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页内置指数（覆盖中国 / 中国香港 / 美国 / 日本 / 韩国 / 欧洲主要市场）。
 * 由配置驱动，不占用 instrument 表（docs/DATABASE.md §3.8 说明）。
 * 最新值由采集调度器刷新，存于内存缓存。
 */
@Component
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    /** 海外指数 secid -> 地区；A 股 secid（1./0. 前缀）按前缀归入「中国」，其余归「海外」 */
    private static final Map<String, String> SECID_REGIONS = Map.ofEntries(
            Map.entry("100.HSI", "中国香港"),
            Map.entry("100.DJIA", "美国"), Map.entry("100.NDX", "美国"), Map.entry("100.SPX", "美国"),
            Map.entry("100.N225", "日本"),
            Map.entry("100.KS11", "韩国"),
            Map.entry("100.FTSE", "欧洲"), Map.entry("100.GDAXI", "欧洲"), Map.entry("100.SX5E", "欧洲"));

    /** 指数定义缓存：secid -> 名称（配置解析结果，不可变） */
    private final Map<String, String> indexDefs;
    private final EastMoneyQuoteClient quoteClient;

    /** 最新指数行情：secid -> 快照（内存态） */
    private final Map<String, IndexQuote> latest = new LinkedHashMap<>();

    /** 上次刷新尝试时间：缓存为空时兜底刷新的冷却闸，避免数据源不可用时每次请求都打源 */
    private volatile OffsetDateTime lastRefreshAttempt;

    public record IndexDef(String secid, String name) {
    }

    public record IndexQuote(
            String symbol,
            String name,
            String region,
            Double lastPrice,
            Double changePercent,
            OffsetDateTime marketTime,
            OffsetDateTime collectedAt,
            String source
    ) {
    }

    public IndexService(EastMoneyQuoteClient quoteClient,
                        @Value("${stockcat.indices:1.000001:上证指数,0.399001:深证成指,0.399006:创业板指,1.000300:沪深300,100.HSI:恒生指数,100.DJIA:道琼斯,100.NDX:纳斯达克,100.SPX:标普500,100.N225:日经225,100.KS11:韩国KOSPI,100.FTSE:英国富时100,100.GDAXI:德国DAX30,100.SX5E:欧洲斯托克50}") String indicesCsv) {
        this.quoteClient = quoteClient;
        this.indexDefs = new LinkedHashMap<>();
        for (String entry : indicesCsv.split(",")) {
            String[] parts = entry.trim().split(":", 2);
            if (parts.length == 2 && !parts[0].isBlank()) {
                indexDefs.put(parts[0].trim(), parts[1].trim());
            }
        }
    }

    public List<IndexDef> definitions() {
        return indexDefs.entrySet().stream()
                .map(e -> new IndexDef(e.getKey(), e.getValue()))
                .toList();
    }

    /** 由调度器或手动刷新接口调用：批量刷新指数缓存。失败时保留旧值并记日志（指数失败不影响个股采集）。 */
    public synchronized void refresh() {
        lastRefreshAttempt = OffsetDateTime.now();
        List<String> secids = new ArrayList<>(indexDefs.keySet());
        if (secids.isEmpty()) {
            return;
        }
        try {
            for (StandardQuote q : quoteClient.fetch(secids)) {
                String secid = secidOf(q.symbol());
                String name = indexDefs.get(secid);
                if (name == null) {
                    continue;
                }
                latest.put(secid, new IndexQuote(
                        q.symbol(), name, regionOf(secid), q.lastPrice(), q.changePercent(),
                        q.marketTime(), OffsetDateTime.now(), q.sourceCode()));
            }
        } catch (EastMoneyQuoteClient.QuoteSourceException e) {
            log.warn("指数行情刷新失败: {}", e.getMessage());
        }
    }

    /** secid "1.000001" -> symbol "000001" */
    private String secidOf(String symbol) {
        // StandardQuote.symbol 是纯代码；用代码反查 secid（代码与 secid 尾部一致）
        for (String secid : indexDefs.keySet()) {
            if (secid.endsWith(symbol)) {
                return secid;
            }
        }
        return symbol;
    }

    /** 指数所属地区：已知海外代码按映射，A 股按市场前缀，其余兜底「海外」 */
    public static String regionOf(String secid) {
        String region = SECID_REGIONS.get(secid);
        if (region != null) {
            return region;
        }
        if (secid.startsWith("1.") || secid.startsWith("0.")) {
            return "中国";
        }
        return "海外";
    }

    public List<IndexQuote> listLatest() {
        boolean empty;
        synchronized (latest) {
            empty = latest.isEmpty();
        }
        // 兜底：调度器只在 A 股交易窗口采集，重启后（尤其盘外）缓存为空时现场拉一次；
        // 30 秒冷却，数据源不可用时不会每个请求都打源
        if (empty && (lastRefreshAttempt == null
                || lastRefreshAttempt.plusSeconds(30).isBefore(OffsetDateTime.now()))) {
            refresh();
        }
        synchronized (latest) {
            return List.copyOf(latest.values());
        }
    }
}
