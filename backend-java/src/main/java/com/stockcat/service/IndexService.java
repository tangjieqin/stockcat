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
 * 首页内置指数（上证指数/深证成指/创业板指）。
 * 由配置驱动，不占用 instrument 表（docs/DATABASE.md §3.8 说明）。
 * 最新值由采集调度器刷新，存于内存缓存。
 */
@Component
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    /** 指数定义缓存：secid -> 名称（配置解析结果，不可变） */
    private final Map<String, String> indexDefs;
    private final EastMoneyQuoteClient quoteClient;

    /** 最新指数行情：secid -> 快照（内存态） */
    private final Map<String, IndexQuote> latest = new LinkedHashMap<>();

    public record IndexDef(String secid, String name) {
    }

    public record IndexQuote(
            String symbol,
            String name,
            Double lastPrice,
            Double changePercent,
            OffsetDateTime marketTime,
            OffsetDateTime collectedAt,
            String source
    ) {
    }

    public IndexService(EastMoneyQuoteClient quoteClient,
                        @Value("${stockcat.indices:1.000001:上证指数,0.399001:深证成指,0.399006:创业板指}") String indicesCsv) {
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

    /** 由调度器调用：批量刷新指数缓存。失败时保留旧值并记日志（指数失败不影响个股采集）。 */
    public void refresh() {
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
                        q.symbol(), name, q.lastPrice(), q.changePercent(),
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

    public List<IndexQuote> listLatest() {
        synchronized (latest) {
            return List.copyOf(latest.values());
        }
    }
}
