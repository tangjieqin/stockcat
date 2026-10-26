package com.stockcat.quote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 腾讯行情客户端（P1.4）：取 ETF 的实时 IOPV 与最新单位净值。
 *
 * <p>为什么是 Java 而不是 Python：溢价告警在关键路径上，P0 已确立"Python 不可用不影响
 * 实时采集与告警"（docs/DATA_SOURCES.md §3.1、§4）。
 *
 * <p>上游格式：GBK 编码、每行 {@code v_sh510300="1~名称~代码~..."}，共 88 个 {@code ~} 分隔字段。
 * 已实测的字段位见下表常量；**不用 idx77（上游自己的溢价率）**——它对 LOF 的基准是 T-1 净值，
 * 却没有任何标注，直接用会把"滞后一天的数"当成实时溢价。
 */
@Component
public class TencentFundClient {

    public static final String SOURCE_CODE = "tencent-qt";

    private static final Logger log = LoggerFactory.getLogger(TencentFundClient.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter QUOTE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Charset GBK = Charset.forName("GBK");

    // 字段位（实测 2026-10-26，88 个字段）
    private static final int IDX_NAME = 1;
    private static final int IDX_CODE = 2;
    private static final int IDX_PRICE = 3;
    private static final int IDX_TIME = 30;
    private static final int IDX_TYPE = 61;
    private static final int IDX_IOPV = 78;
    private static final int IDX_NAV = 81;
    private static final int MIN_FIELDS = 82;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Duration timeout;

    public TencentFundClient(@Value("${stockcat.fund.quote-source.base-url:https://qt.gtimg.cn}") String baseUrl,
                             @Value("${stockcat.fund.quote-source.timeout-seconds:5}") long timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * 批量取基金行情。symbols 为 6 位代码（如 510300），一次请求即可。
     * 无法映射的代码（如北交所）由调用方提前过滤，这里返回空列表而不猜。
     */
    public List<FundQuote> fetch(List<String> symbols) {
        List<String> codes = new ArrayList<>();
        for (String symbol : symbols) {
            String code = toTencentCode(symbol);
            if (code != null) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            return List.of();
        }
        String url = baseUrl + "/q=" + String.join(",", codes);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", "StockCat/0.1")
                .header("Referer", "https://gu.qq.com/")
                .GET()
                .build();
        try {
            // 必须拿字节再按 GBK 解码：响应头里的 charset 不可靠，直接当 UTF-8 会得到乱码名称
            HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new FundSourceException("UPSTREAM_ERROR", "腾讯行情 HTTP " + resp.statusCode());
            }
            return parse(new String(resp.body(), GBK));
        } catch (FundSourceException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            log.warn("腾讯基金行情超时: {}", e.getMessage());
            throw new FundSourceException("UPSTREAM_TIMEOUT", "腾讯基金行情超时: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FundSourceException("DATA_SOURCE_UNAVAILABLE", "腾讯基金行情请求被中断", e);
        } catch (Exception e) {
            log.warn("腾讯基金行情不可用: {}", e.getMessage());
            throw new FundSourceException("DATA_SOURCE_UNAVAILABLE", "腾讯基金行情不可用: " + e.getMessage(), e);
        }
    }

    /** 解析已解码的响应文本。缺字段置 null，不补 0。 */
    List<FundQuote> parse(String body) {
        List<FundQuote> quotes = new ArrayList<>();
        for (String raw : body.split(";")) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (eq < 0 || !line.startsWith("v_")) {
                continue;
            }
            String value = line.substring(eq + 1).trim();
            if (value.length() < 2 || !value.startsWith("\"")) {
                continue;   // v_pv_none_match="1" 之类的错误行
            }
            value = value.substring(1, value.endsWith("\"") ? value.length() - 1 : value.length());
            String[] f = value.split("~", -1);
            if (f.length < MIN_FIELDS) {
                continue;
            }
            quotes.add(new FundQuote(
                    text(f, IDX_CODE),
                    text(f, IDX_NAME),
                    text(f, IDX_TYPE),
                    number(f, IDX_PRICE),
                    number(f, IDX_IOPV),
                    number(f, IDX_NAV),
                    marketTime(text(f, IDX_TIME))
            ));
        }
        return quotes;
    }

    /**
     * 6 位代码 → 腾讯代码（sh/sz 前缀）。规则与 {@link SymbolMapping} 保持一致：
     * 6/5/9 开头沪市，0/1/2/3 开头深市，其余（北交所 8xx/4xx）不支持，返回 null。
     */
    public static String toTencentCode(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        char head = symbol.charAt(0);
        if (head == '6' || head == '5' || head == '9') {
            return "sh" + symbol;
        }
        if (head == '0' || head == '1' || head == '2' || head == '3') {
            return "sz" + symbol;
        }
        return null;
    }

    private static String text(String[] fields, int index) {
        String raw = fields[index];
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static Double number(String[] fields, int index) {
        String raw = text(fields, index);
        if (raw == null) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static OffsetDateTime marketTime(String raw) {
        if (raw == null || raw.length() != 14) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, QUOTE_TIME).atZone(CN).toOffsetDateTime();
        } catch (Exception e) {
            return null;
        }
    }
}
