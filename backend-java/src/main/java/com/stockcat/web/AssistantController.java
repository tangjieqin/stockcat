package com.stockcat.web;

import com.stockcat.assistant.AssistantClient;
import com.stockcat.web.dto.AssistantDtos.AskRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 助手（P2）：前端唯一入口，转发到 Python Agent。
 *
 * <p>这里只有代理，没有业务判断——数据口径与只读边界由 Java 的 /api/v1/agent/tools 与
 * Python 侧共同保证；AI 生成的内容一律是"草稿/建议"，不写库。
 */
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final AssistantClient client;

    public AssistantController(AssistantClient client) {
        this.client = client;
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@Valid @RequestBody AskRequest request) {
        return client.ask(request.question().trim());
    }

    /** 配置与服务状态：供 AI 设置页显示，不做探活请求、不回显 API Key。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return client.status();
    }
}
