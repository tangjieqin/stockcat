package com.stockcat.web;

import com.stockcat.common.ApiException;
import com.stockcat.common.ErrorCode;
import com.stockcat.quote.EastMoneyQuoteClient;
import com.stockcat.quote.StandardQuote;
import com.stockcat.service.IndexService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置指数行情（配置驱动，不占用 instrument 表）。P0.9 首页将使用本接口。
 */
@RestController
@RequestMapping("/api/v1")
public class IndexController {

    /** 自定义指数单次最多查询数，防止把接口当免费行情代理刷 */
    private static final int MAX_CUSTOM_SECIDS = 20;

    private final IndexService indexService;
    private final EastMoneyQuoteClient quoteClient;

    public IndexController(IndexService indexService, EastMoneyQuoteClient quoteClient) {
        this.indexService = indexService;
        this.quoteClient = quoteClient;
    }

    @GetMapping("/indices")
    public List<IndexService.IndexQuote> indices() {
        return indexService.listLatest();
    }

    /** 手动刷新：调度器只在 A 股交易窗口采集，海外指数收盘更新后可用此接口立即拉最新值 */
    @PostMapping("/indices/refresh")
    public List<IndexService.IndexQuote> refresh() {
        indexService.refresh();
        return indexService.listLatest();
    }

    /**
     * 自定义指数实时行情（不落库、不缓存，现场拉取）：用户在首页「添加指数」时使用。
     * secids 为东财 secid（如 1.000688、100.DJIA），名称与地区由数据源/规则自动识别；
     * 返回的 symbol 即请求的 secid（方便前端与本地保存的自定义列表对应）。
     * 上限 20 个；数据源不可用返回 503 DATA_SOURCE_UNAVAILABLE。
     */
    @GetMapping("/indices/custom")
    public List<IndexService.IndexQuote> custom(@RequestParam("secids") String secidsCsv) {
        List<String> secids = new ArrayList<>();
        for (String raw : secidsCsv.split(",")) {
            String secid = raw.trim();
            if (!secid.isEmpty() && secids.size() < MAX_CUSTOM_SECIDS) {
                secids.add(secid);
            }
        }
        if (secids.isEmpty()) {
            return List.of();
        }
        List<StandardQuote> quotes;
        try {
            quotes = quoteClient.fetch(secids);
        } catch (EastMoneyQuoteClient.QuoteSourceException e) {
            throw new ApiException(ErrorCode.DATA_SOURCE_UNAVAILABLE, "行情源不可用：" + e.getMessage());
        }
        List<IndexService.IndexQuote> result = new ArrayList<>();
        for (StandardQuote q : quotes) {
            String secid = secidOf(q.symbol(), secids);
            result.add(new IndexService.IndexQuote(
                    secid, q.name(), IndexService.regionOf(secid), q.lastPrice(), q.changePercent(),
                    q.marketTime(), OffsetDateTime.now(), q.sourceCode()));
        }
        return result;
    }

    /** 数据源返回纯代码，用请求里的 secid 尾部反查完整 secid（算地区用） */
    private String secidOf(String symbol, List<String> secids) {
        for (String secid : secids) {
            if (secid.endsWith(symbol)) {
                return secid;
            }
        }
        return symbol;
    }
}
