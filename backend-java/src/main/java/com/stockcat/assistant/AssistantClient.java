package com.stockcat.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.common.ApiException;
import com.stockcat.common.ErrorCode;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 助手客户端：Java → Python Agent（POST /agent/v1/assistant/ask）。
 *
 * <p>为什么要有这层代理：前端只跟 Java 说话（docs/ARCHITECTURE.md），
 * 把 Python 的端口和 CORS 暴露给浏览器会破坏这条约定，也没法在 Java 侧统一做超时与降级。
 *
 * <p><b>字段原样透传</b>：回答体由 Python 的契约定义（docs/API.md §11），Java 不重写字段，
 * 避免同一份 schema 在两处漂移。Java 只负责搬运 + 错误翻译。
 *
 * <p><b>失败语义</b>：Python 没起来 / 超时 → {@code DATA_SOURCE_UNAVAILABLE}。
 * AI 不在关键路径上，这个异常只影响 AI 页面，行情、告警、持仓照常。
 */
@Component
public class AssistantClient {

    private static final Logger log = LoggerFactory.getLogger(AssistantClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;

    public AssistantClient(
            @Value("${stockcat.assistant.base-url:http://127.0.0.1:8100}") String baseUrl,
            @Value("${stockcat.assistant.timeout-seconds:60}") long timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        // 必须固定 HTTP/1.1：默认的 HTTP_2 会对明文连接发 h2c 升级头，
        // uvicorn 收到带 Upgrade 的 POST 会直接回 400"请求参数不合法"，很难从错误里看出原因
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /** 一问一答。返回 Python 的原始响应体（含 answer / toolCalls / dataAsOf / degraded）。 */
    public Map<String, Object> ask(String question) {
        return post("/agent/v1/assistant/ask", Map.of("question", question));
    }

    /**
     * 解释单条告警事件。payload 由 {@code AlertExplainService} 组装：
     * 事件快照 + 当前行情/指标，Python 只负责把事实讲清楚。
     */
    public Map<String, Object> explainAlert(Map<String, Object> payload) {
        return post("/agent/v1/alerts/explain", payload);
    }

    /**
     * 生成每日复盘草稿（**不落库**）。Java 只转发日期：数据由 Python 经
     * {@code /api/v1/agent/tools} 自取，保证草稿与页面看到的是同一份口径。
     * 落库与确认在 {@code RecapDraftService}，AI 拿不到写能力。
     */
    public Map<String, Object> recapDraft(String date) {
        return post("/agent/v1/recap/draft", Map.of("date", date));
    }

    private Map<String, Object> post(String path, Map<String, Object> payload) {
        String body = toJson(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "StockCat/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw mapError(response.statusCode(), response.body());
        }
        return parseObject(response.body());
    }

    /** Agent 配置状态。不可用时不抛异常——状态页要能显示"起了/没起"，而不是整页报错。 */
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/agent/v1/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = send(request);
            result.put("available", response.statusCode() == 200);
            if (response.statusCode() == 200) {
                result.put("agent", parseObject(response.body()));
            } else {
                result.put("error", "HTTP " + response.statusCode());
            }
        } catch (ApiException e) {
            result.put("available", false);
            result.put("error", e.getMessage());
        }
        result.put("baseUrl", baseUrl);
        return result;
    }

    /**
     * 供 {@code /system/status} 用的精简状态：只回答"Python 起没起、模型配了没、几个工具"。
     *
     * <p>三条约束与 {@link #status()} 一致：不抛异常（Python 没起来是**正常状态**，
     * 不是接口故障）、不回显 API Key、不发会消耗 token 的请求。
     */
    public Map<String, Object> pythonService() {
        Map<String, Object> raw = status();
        boolean available = Boolean.TRUE.equals(raw.get("available"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", available ? "UP" : "DOWN");
        out.put("baseUrl", raw.get("baseUrl"));
        if (!available) {
            out.put("error", raw.get("error"));
            return out;
        }

        Map<String, Object> agent = asMap(raw.get("agent"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", agent.getOrDefault("status", "UNKNOWN"));
        summary.put("modelConfigured", Boolean.TRUE.equals(agent.get("modelConfigured")));
        summary.put("model", agent.get("model"));
        // 只报工具个数，不搬整个白名单：系统状态页要的是"几个能力被打开"，不是清单
        summary.put("toolCount", asList(agent.get("tools")).size());
        out.put("agent", summary);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> asList(Object value) {
        return value instanceof java.util.List ? (java.util.List<Object>) value : java.util.List.of();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            log.warn("AI 服务超时: {}s", timeout.toSeconds());
            throw new ApiException(ErrorCode.DATA_SOURCE_UNAVAILABLE,
                    "AI 服务响应超时（" + timeout.toSeconds() + "s），请重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.DATA_SOURCE_UNAVAILABLE, "AI 请求被中断");
        } catch (java.net.ConnectException e) {
            log.warn("AI 服务未启动: {}", baseUrl);
            throw new ApiException(ErrorCode.DATA_SOURCE_UNAVAILABLE,
                    "AI 服务未启动（" + baseUrl + "），请先启动 backend-python："
                            + "uvicorn app.main:app --port 8100");
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("AI 服务不可用: {}", detail);
            throw new ApiException(ErrorCode.DATA_SOURCE_UNAVAILABLE, "AI 服务不可用: " + detail);
        }
    }

    /** 优先采信 Python 的错误码（结构同 API.md §1），映射失败按状态兜底。 */
    private ApiException mapError(int status, String body) {
        String code = null;
        String message = null;
        try {
            JsonNode root = objectMapper.readTree(body);
            code = root.hasNonNull("code") ? root.get("code").asText() : null;
            message = root.hasNonNull("message") ? root.get("message").asText() : null;
        } catch (Exception ignored) {
            // 非 JSON（网关错误页之类），走状态码兜底
        }
        String detail = message == null || message.isBlank() ? "AI 服务返回 HTTP " + status : message;
        return new ApiException(errorCodeOf(code, status), detail);
    }

    private static ErrorCode errorCodeOf(String code, int status) {
        if (code != null) {
            for (ErrorCode candidate : ErrorCode.values()) {
                if (candidate.code().equals(code)) {
                    return candidate;
                }
            }
        }
        return status == 400 ? ErrorCode.VALIDATION_ERROR : ErrorCode.DATA_SOURCE_UNAVAILABLE;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObject(String body) {
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.DATA_SOURCE_UNAVAILABLE, "AI 服务响应解析失败: " + e.getMessage());
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "请求体序列化失败");
        }
    }
}
