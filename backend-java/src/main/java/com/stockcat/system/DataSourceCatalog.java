package com.stockcat.system;

import com.stockcat.collector.CollectorStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源目录：项目到底从哪儿取数、开没开、什么时候跑、上次结果如何。
 *
 * <p>这些信息以前只散落在配置文件、日志和各个客户端类里，用户想确认"净值是不是没同步"
 * 得同时看三处。这里把它们收在一处，供「数据源」页只读展示。
 *
 * <p><b>不变的边界</b>：只报地址、开关、频率与运行结果，**不报凭据**。
 * 东方财富/腾讯/天天基金都不需要 key，唯一的密钥类配置（ServerChan send-key）不在本目录里。
 *
 * <p>没有运行统计的数据源如实写 {@code stats=null}，前端显示"—"，不用"看起来正常"兜底。
 */
@Component
public class DataSourceCatalog {

    private static final String UNKNOWN = "UNKNOWN";

    private final CollectorStats quoteStats;
    private final JobRunStats jobStats;

    private final String quoteBaseUrl;
    private final int quoteIntervalSeconds;
    private final String collectWindows;
    private final boolean collectEnabled;

    private final String fundQuoteBaseUrl;

    private final String historyBaseUrl;
    private final boolean historyEnabled;
    private final String historyCron;

    private final String fundNavBaseUrl;
    private final boolean fundNavEnabled;
    private final String fundNavCron;

    public DataSourceCatalog(CollectorStats quoteStats,
                             JobRunStats jobStats,
                             @Value("${stockcat.quote-source.base-url:https://push2.eastmoney.com}") String quoteBaseUrl,
                             @Value("${stockcat.collect.interval-seconds:15}") int quoteIntervalSeconds,
                             @Value("${stockcat.calendar.collect-windows:09:25-11:35,12:55-15:10}") String collectWindows,
                             @Value("${stockcat.collect.enabled:true}") boolean collectEnabled,
                             @Value("${stockcat.fund.quote-source.base-url:https://qt.gtimg.cn}") String fundQuoteBaseUrl,
                             @Value("${stockcat.history-source.base-url:http://127.0.0.1:8100}") String historyBaseUrl,
                             @Value("${stockcat.history.sync.enabled:true}") boolean historyEnabled,
                             @Value("${stockcat.history.sync.cron:0 35 15 * * MON-FRI}") String historyCron,
                             @Value("${stockcat.fund.nav-source.base-url:${stockcat.history-source.base-url:http://127.0.0.1:8100}}") String fundNavBaseUrl,
                             @Value("${stockcat.fund.nav.enabled:true}") boolean fundNavEnabled,
                             @Value("${stockcat.fund.nav.cron:0 30 21 * * MON-FRI}") String fundNavCron) {
        this.quoteStats = quoteStats;
        this.jobStats = jobStats;
        this.quoteBaseUrl = quoteBaseUrl;
        this.quoteIntervalSeconds = quoteIntervalSeconds;
        this.collectWindows = collectWindows;
        this.collectEnabled = collectEnabled;
        this.fundQuoteBaseUrl = fundQuoteBaseUrl;
        this.historyBaseUrl = historyBaseUrl;
        this.historyEnabled = historyEnabled;
        this.historyCron = historyCron;
        this.fundNavBaseUrl = fundNavBaseUrl;
        this.fundNavEnabled = fundNavEnabled;
        this.fundNavCron = fundNavCron;
    }

    /** 四个数据源，顺序即页面展示顺序（实时 → 参考 → 历史 → 净值）。 */
    public List<Map<String, Object>> list() {
        return List.of(quote(), fundQuote(), history(), fundNav());
    }

    private Map<String, Object> quote() {
        Map<String, Object> item = base("quote", "实时行情", "东方财富 push2", quoteBaseUrl);
        item.put("enabled", collectEnabled);
        item.put("schedule", "交易时段每 %d 秒".formatted(quoteIntervalSeconds));
        item.put("note", "自选、监控标的与指数的现价；未开市或超时按 STALE 标注，缺失字段一律 null，不按 0 显示。");
        item.put("stats", quoteStats());
        return item;
    }

    private Map<String, Object> fundQuote() {
        Map<String, Object> item = base("fund-quote", "基金 IOPV", "腾讯行情", fundQuoteBaseUrl);
        item.put("enabled", collectEnabled);
        item.put("schedule", "随实时行情采集");
        item.put("note", "ETF 的盘中参考净值；LOF 没有 IOPV，只能等当晚净值。本项无独立运行统计。");
        item.put("stats", null);
        return item;
    }

    private Map<String, Object> history() {
        Map<String, Object> item = base("history", "历史日 K", "东方财富（经 Python）", historyBaseUrl);
        item.put("enabled", historyEnabled);
        item.put("schedule", "交易日收盘后");
        item.put("cron", historyCron);
        item.put("note", "前复权/不复权日线，指标计算与持仓快照都依赖它；Python 未启动时每轮只记一次失败。");
        item.put("stats", jobStats.view(JobRunStats.HISTORY_SYNC));
        return item;
    }

    private Map<String, Object> fundNav() {
        Map<String, Object> item = base("fund-nav", "基金净值", "天天基金（经 Python）", fundNavBaseUrl);
        item.put("enabled", fundNavEnabled);
        item.put("schedule", "交易日 21:30 后");
        item.put("cron", fundNavCron);
        item.put("note", "基金日终净值，LOF 溢价率的基准；T 日净值当晚才公布，缺失由次日窗口幂等补齐。");
        item.put("stats", jobStats.view(JobRunStats.FUND_NAV_SYNC));
        return item;
    }

    private static Map<String, Object> base(String key, String name, String provider, String baseUrl) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("name", name);
        item.put("provider", provider);
        item.put("baseUrl", baseUrl);
        item.put("cron", null);
        return item;
    }

    /** 实时采集的统计口径与 {@code /system/status.quoteCollector} 保持一致。 */
    private Map<String, Object> quoteStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean everAttempted = quoteStats.getLastAttemptAt() != null;
        out.put("status", !everAttempted ? UNKNOWN : collectorStatus(quoteStats));
        out.put("lastAttemptAt", quoteStats.getLastAttemptAt());
        out.put("lastSuccessAt", quoteStats.getLastSuccessAt());
        out.put("lastError", quoteStats.getLastErrorCode());
        out.put("consecutiveFailures", quoteStats.getConsecutiveFailures());
        out.put("attempts", quoteStats.getAttemptCount());
        out.put("successes", quoteStats.getSuccessCount());
        out.put("summary", "成功 %d 次、失败 %d 只次"
                .formatted(quoteStats.getSuccessCount(), quoteStats.getFailedQuoteCount()));
        return out;
    }

    /** 连续失败 ≥3 才算 DEGRADED：偶发一次失败不该让整块变红。 */
    public static String collectorStatus(CollectorStats stats) {
        return stats.getConsecutiveFailures() >= 3 ? "DEGRADED" : "UP";
    }
}
