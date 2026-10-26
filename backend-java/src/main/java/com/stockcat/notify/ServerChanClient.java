package com.stockcat.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Server酱推送客户端（https://sct.ftqq.com）。
 * POST https://sctapi.ftqq.com/{sendKey}.send，参数 title + desp(Markdown)。
 * 响应 {"code":0,...} 视为成功。SendKey 来自 .env，绝不写日志。
 */
@Component
public class ServerChanClient {

    private static final Logger log = LoggerFactory.getLogger(ServerChanClient.class);

    private final HttpClient httpClient;
    private final String sendKey;
    private final Duration timeout;

    public ServerChanClient(@Value("${stockcat.notify.serverchan.send-key:}") String sendKey,
                            @Value("${stockcat.notify.timeout-seconds:8}") long timeoutSeconds) {
        this.sendKey = sendKey == null ? "" : sendKey.trim();
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public boolean configured() {
        return !sendKey.isEmpty();
    }

    /** 发送成功返回 provider 消息 id；失败抛异常（调用方记录 FAILED）。 */
    public String send(String title, String despMarkdown) {
        if (!configured()) {
            throw new NotifierException("SEND_KEY_NOT_CONFIGURED", "Server酱 SendKey 未配置");
        }
        String body = "title=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&desp=" + URLEncoder.encode(despMarkdown, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://sctapi.ftqq.com/" + sendKey + ".send"))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new NotifierException("HTTP_" + resp.statusCode(), "Server酱 HTTP " + resp.statusCode());
            }
            // 简单解析 code 字段
            String text = resp.body();
            if (text.contains("\"code\":0")) {
                String pushId = extract(text, "pushid");
                return pushId != null ? pushId : "ok";
            }
            String errmsg = extract(text, "message");
            throw new NotifierException("API_ERROR", "Server酱返回错误: "
                    + (errmsg != null ? errmsg : text.substring(0, Math.min(120, text.length()))));
        } catch (NotifierException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Server酱发送失败: {}", e.getMessage());
            throw new NotifierException("SEND_FAILED", "发送失败: " + e.getMessage());
        }
    }

    private static String extract(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i);
        int start = json.indexOf('"', colon);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        return end > start ? json.substring(start + 1, end) : null;
    }

    public static class NotifierException extends RuntimeException {
        private final String code;

        public NotifierException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
