package com.stockcat.fund;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.quote.FundSourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 基金净值客户端：Java → Python 适配器（GET /internal/v1/fund-nav）。
 *
 * <p>为什么不直接由 Java 抓天天基金：抓取与解析归 Python（架构决策 D1），
 * 实时 IOPV 才走 Java；这里是"日终净值"这条冷路径。
 *
 * <p>失败一律抛 {@link FundSourceException}，由调用方（同步任务）吞掉并记录，
 * 不能让一条净值把实时采集和告警带崩。
 */
@Component
public class FundNavClient {

    public static final String SOURCE_CODE = "eastmoney-f10-lsjz";

    private static final Logger log = LoggerFactory.getLogger(FundNavClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;

    public FundNavClient(
            @Value("${stockcat.fund.nav-source.base-url:${stockcat.history-source.base-url:http://127.0.0.1:8100}}") String baseUrl,
            @Value("${stockcat.fund.nav-source.timeout-seconds:15}") long timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * @param fundCode 6 位基金代码（与 instrument.symbol 相同）
     * @param limit    期数，1~250
     */
    public FundNavFetch fetch(String fundCode, int limit) {
        String url = baseUrl + "/internal/v1/fund-nav?fundCode=" + fundCode + "&limit=" + limit;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", "StockCat/0.1")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw mapError(resp.statusCode(), resp.body());
            }
            return parse(resp.body());
        } catch (FundSourceException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            log.warn("净值源超时: {} {}", fundCode, e.getMessage());
            throw new FundSourceException("UPSTREAM_TIMEOUT", "净值源超时: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FundSourceException("DATA_SOURCE_UNAVAILABLE", "净值请求被中断", e);
        } catch (java.net.ConnectException e) {
            log.warn("净值源未启动: {}", baseUrl);
            throw new FundSourceException("DATA_SOURCE_UNAVAILABLE",
                    "净值源未启动或拒绝连接（" + baseUrl + "），请先启动 backend-python", e);
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("净值源不可用: {} {}", fundCode, detail);
            throw new FundSourceException("DATA_SOURCE_UNAVAILABLE", "净值源不可用: " + detail, e);
        }
    }

    FundNavFetch parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            List<FundNavPoint> items = new ArrayList<>();
            for (JsonNode node : root.path("items")) {
                LocalDate date = parseDate(text(node, "date"));
                Double nav = optionalDouble(node, "nav");
                if (date == null || nav == null) {
                    continue;
                }
                items.add(new FundNavPoint(date, nav,
                        optionalDouble(node, "accNav"), optionalDouble(node, "changePercent")));
            }
            return new FundNavFetch(
                    text(root, "fundCode"),
                    root.path("requestedLimit").asInt(items.size()),
                    parseTimestamp(text(root, "fetchedAt")),
                    root.path("totalCount").asInt(items.size()),
                    root.path("skippedRows").asInt(0),
                    items
            );
        } catch (Exception e) {
            throw new FundSourceException("INVALID_RESPONSE", "净值源响应解析失败: " + e.getMessage(), e);
        }
    }

    FundSourceException mapError(int status, String body) {
        String code = null;
        String message = null;
        try {
            JsonNode root = objectMapper.readTree(body);
            code = text(root, "code");
            message = text(root, "message");
        } catch (Exception ignored) {
            // 上游返回非 JSON（网关错误页等），按状态码兜底
        }
        if (code == null || code.isBlank()) {
            code = switch (status) {
                case 404 -> "NO_DATA";
                case 400, 422 -> "VALIDATION_ERROR";
                case 504 -> "UPSTREAM_TIMEOUT";
                case 502 -> "UPSTREAM_ERROR";
                default -> "DATA_SOURCE_UNAVAILABLE";
            };
        }
        String detail = message == null || message.isBlank() ? "HTTP " + status : message;
        return new FundSourceException(code, "净值源返回错误: " + detail);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Double optionalDouble(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        try {
            return Double.parseDouble(node.get(field).asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static OffsetDateTime parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
