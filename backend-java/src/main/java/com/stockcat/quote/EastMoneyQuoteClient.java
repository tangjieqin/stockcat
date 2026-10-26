package com.stockcat.quote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 东方财富 push2 批量实时行情客户端（P0.3 已验证可用）。
 * 响应样例: {"data":{"diff":[{"f2":1335.0,"f3":1.25,"f5":57686,"f6":7677216283.0,
 *   "f12":"600519","f13":1,"f14":"贵州茅台","f18":1318.49,"f124":1792743118}]}}
 * f2 最新价 / f3 涨跌幅% / f5 成交量(手) / f6 成交额(元) / f12 代码 / f14 名称 / f18 昨收 / f124 行情时间(epoch 秒)
 */
@Component
public class EastMoneyQuoteClient {

    public static final String SOURCE_CODE = "eastmoney-push2";

    private static final Logger log = LoggerFactory.getLogger(EastMoneyQuoteClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;

    public EastMoneyQuoteClient(@Value("${stockcat.quote-source.base-url:https://push2.eastmoney.com}") String baseUrl,
                                @Value("${stockcat.quote-source.timeout-seconds:5}") long timeoutSeconds) {
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    /**
     * 批量拉取行情。secids 形如 ["1.600519","0.000001"]。
     * 数据源不可用/超时抛出 QuoteSourceException，由调用方决定降级策略。
     */
    public List<StandardQuote> fetch(List<String> secids) {
        if (secids.isEmpty()) {
            return List.of();
        }
        String url = baseUrl + "/api/qt/ulist.np/get?secids=" + String.join(",", secids)
                + "&fields=f2,f3,f5,f6,f12,f13,f14,f18,f124&fltt=2&invt=2";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", "StockCat/0.1")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new QuoteSourceException("行情源 HTTP " + resp.statusCode());
            }
            return parse(resp.body());
        } catch (QuoteSourceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("行情源请求失败: {}", e.getMessage());
            throw new QuoteSourceException("行情源请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析响应 JSON。缺失字段置 null（不补 0），供单元测试覆盖。
     */
    List<StandardQuote> parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode diff = root.path("data").path("diff");
            List<StandardQuote> result = new ArrayList<>();
            for (JsonNode item : diff) {
                result.add(StandardQuote.of(
                        text(item, "f12"),
                        text(item, "f14"),
                        optionalDouble(item, "f2"),
                        optionalDouble(item, "f18"),
                        optionalDouble(item, "f3"),
                        optionalDouble(item, "f5"),
                        optionalDouble(item, "f6"),
                        item.hasNonNull("f124") ? item.get("f124").asLong() : null,
                        SOURCE_CODE
                ));
            }
            return result;
        } catch (Exception e) {
            throw new QuoteSourceException("行情源响应解析失败: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Double optionalDouble(JsonNode node, String field) {
        // 停牌等场景东财会返回 "-"，视为缺失
        if (!node.hasNonNull(field)) {
            return null;
        }
        String raw = node.get(field).asText();
        if (raw.equals("-")) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static class QuoteSourceException extends RuntimeException {
        public QuoteSourceException(String message) {
            super(message);
        }

        public QuoteSourceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
