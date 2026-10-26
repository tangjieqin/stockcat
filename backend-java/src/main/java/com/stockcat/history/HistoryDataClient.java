package com.stockcat.history;

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
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 历史行情客户端：Java → Python 适配器（GET /internal/v1/kline）。
 *
 * <p>职责边界：本类只负责「取数 + 字段映射 + 错误翻译」，不做业务判定
 * （复权能否用于指标、负价 bar 是否入库、未收盘 bar 是否拒写，全部由 KlineSyncService 决定）。
 *
 * <p>失败语义：任何失败都抛 {@link HistorySourceException}，带明确 code。
 * 调用方（同步任务）必须吞掉异常只记失败，不能让历史行情影响 P0 的实时采集与告警。
 */
@Component
public class HistoryDataClient {

    /** 上游来源标识，与 Python 返回的 source 字段一致。 */
    public static final String SOURCE_CODE = "eastmoney-push2his";

    private static final Logger log = LoggerFactory.getLogger(HistoryDataClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;

    public HistoryDataClient(@Value("${stockcat.history-source.base-url:http://127.0.0.1:8100}") String baseUrl,
                             @Value("${stockcat.history-source.timeout-seconds:15}") long timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * 拉取日 K。
     *
     * @param secid  东财 secid，由 SymbolMapping 生成
     * @param adjust qfq / hfq / raw
     * @param limit  1~500
     * @param end    截止交易日（含），null 表示由上游取最新
     */
    public KlineFetch fetch(String secid, String adjust, int limit, LocalDate end) {
        String url = baseUrl + "/internal/v1/kline?secid=" + secid
                + "&period=daily&adjust=" + adjust + "&limit=" + limit
                + (end != null ? "&end=" + end : "");
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
        } catch (HistorySourceException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            log.warn("历史行情源超时: {} {}", secid, e.getMessage());
            throw new HistorySourceException("UPSTREAM_TIMEOUT", "历史行情源超时: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HistorySourceException("DATA_SOURCE_UNAVAILABLE", "历史行情请求被中断", e);
        } catch (java.net.ConnectException e) {
            // 最常见的一种：Python 适配器没启动。消息要能直接告诉人下一步做什么
            log.warn("历史行情源未启动: {}", baseUrl);
            throw new HistorySourceException("DATA_SOURCE_UNAVAILABLE",
                    "历史行情源未启动或拒绝连接（" + baseUrl + "），请先启动 backend-python", e);
        } catch (Exception e) {
            // 其余网络异常同样不外抛：历史行情失败不能影响 P0 的实时采集与告警
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("历史行情源不可用: {} {}", secid, detail);
            throw new HistorySourceException("DATA_SOURCE_UNAVAILABLE", "历史行情源不可用: " + detail, e);
        }
    }

    /**
     * 解析成功响应。字段缺失置 null，不补 0。
     */
    KlineFetch parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            List<DailyBar> bars = new ArrayList<>();
            for (JsonNode node : root.path("bars")) {
                bars.add(new DailyBar(
                        parseDate(node.path("date").asText(null)),
                        optionalDouble(node, "open"),
                        optionalDouble(node, "high"),
                        optionalDouble(node, "low"),
                        optionalDouble(node, "close"),
                        optionalDouble(node, "volume"),
                        optionalDouble(node, "amount"),
                        optionalDouble(node, "changePercent"),
                        optionalDouble(node, "turnoverRate")
                ));
            }
            return new KlineFetch(
                    text(root, "source") == null ? SOURCE_CODE : text(root, "source"),
                    text(root, "secid"),
                    text(root, "symbol"),
                    text(root, "adjust"),
                    root.path("requestedLimit").asInt(bars.size()),
                    parseTimestamp(text(root, "fetchedAt")),
                    root.path("barCount").asInt(bars.size()),
                    root.path("skippedBars").asInt(0),
                    bars
            );
        } catch (Exception e) {
            throw new HistorySourceException("INVALID_RESPONSE", "历史行情源响应解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把非 200 响应翻译成带 code 的异常。优先采信响应体里的 code（Python 已统一错误结构），
     * 响应体不可解析时按 HTTP 状态兜底。
     */
    HistorySourceException mapError(int status, String body) {
        String code = null;
        String message = null;
        try {
            JsonNode root = objectMapper.readTree(body);
            code = text(root, "code");
            message = text(root, "message");
        } catch (Exception ignored) {
            // 上游返回了非 JSON（如网关错误页），走状态码兜底
        }
        if (code == null || code.isBlank()) {
            code = switch (status) {
                case 404 -> "NO_DATA";
                case 400, 422 -> "NOT_SUPPORTED";
                case 504 -> "UPSTREAM_TIMEOUT";
                case 502 -> "UPSTREAM_ERROR";
                default -> "DATA_SOURCE_UNAVAILABLE";
            };
        }
        String detail = message == null || message.isBlank() ? "HTTP " + status : message;
        return new HistorySourceException(code, "历史行情源返回错误: " + detail);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Double optionalDouble(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String raw = node.get(field).asText();
        if (raw.equals("-") || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return LocalDate.parse(raw);
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
