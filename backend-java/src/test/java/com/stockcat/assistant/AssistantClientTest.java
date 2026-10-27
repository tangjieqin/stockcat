package com.stockcat.assistant;

import com.stockcat.common.ApiException;
import com.stockcat.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 客户端只关心两件事：Python 没起来时给出可操作的提示，状态接口不能把整页拖挂。
 * 用 127.0.0.1:1（必然拒绝连接）代替"没启动的 Python"，不依赖外部服务。
 */
class AssistantClientTest {

    private static AssistantClient offline() {
        return new AssistantClient("http://127.0.0.1:1", 1);
    }

    @Test
    void unavailableServiceMapsToDataSourceUnavailable() {
        assertThatThrownBy(() -> offline().ask("我的持仓怎么样"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DATA_SOURCE_UNAVAILABLE);
                    // 提示必须能直接告诉人下一步做什么
                    assertThat(e.getMessage()).contains("backend-python");
                });
    }

    @Test
    void statusReportsUnavailableInsteadOfThrowing() {
        Map<String, Object> status = offline().status();

        assertThat(status.get("available")).isEqualTo(false);
        assertThat(status.get("error")).asString().isNotBlank();
        assertThat(status.get("baseUrl")).isEqualTo("http://127.0.0.1:1");
    }

    @Test
    void pythonServiceKeepsErrorAndOmitsAgentWhenDown() {
        Map<String, Object> out = offline().pythonService();

        assertThat(out).containsEntry("status", "DOWN");
        assertThat(out.get("error")).asString().contains("backend-python");
        assertThat(out).doesNotContainKey("agent");
    }

    @Test
    void pythonServiceSummarisesToolsAsCountOnly() {
        AssistantClient client = spying(Map.of(
                "available", true,
                "baseUrl", "http://127.0.0.1:8100",
                "agent", Map.of(
                        "status", "UP",
                        "modelConfigured", false,
                        "model", "",
                        "tools", java.util.List.of("positions", "market-brief", "recaps"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) client.pythonService().get("agent");

        assertThat(agent).containsEntry("status", "UP")
                .containsEntry("modelConfigured", false)
                .containsEntry("toolCount", 3);
        // 白名单细节留在 /assistant/status 与 API.md §10，不塞进系统状态
        assertThat(agent).doesNotContainKey("tools");
    }

    @Test
    void pythonServiceDegradesToUnknownWhenAgentBodyMissing() {
        AssistantClient client = spying(Map.of("available", true, "baseUrl", "http://x"));

        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) client.pythonService().get("agent");

        assertThat(agent).containsEntry("status", "UNKNOWN")
                .containsEntry("modelConfigured", false)
                .containsEntry("toolCount", 0);
    }

    private static AssistantClient spying(Map<String, Object> status) {
        AssistantClient client = org.mockito.Mockito.spy(new AssistantClient("http://127.0.0.1:8100", 5));
        org.mockito.Mockito.doReturn(status).when(client).status();
        return client;
    }
}
