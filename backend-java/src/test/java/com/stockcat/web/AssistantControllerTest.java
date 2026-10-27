package com.stockcat.web;

import com.stockcat.assistant.AssistantClient;
import com.stockcat.web.dto.AssistantDtos.AskRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 代理层只做搬运：去掉首尾空白后转发，响应原样返回（字段由 Python 契约定义）。
 */
class AssistantControllerTest {

    @Test
    void trimsQuestionBeforeDelegating() {
        AssistantClient client = Mockito.mock(AssistantClient.class);
        when(client.ask("我的持仓怎么样")).thenReturn(Map.of("mode", "rule_based"));
        AssistantController controller = new AssistantController(client);

        Map<String, Object> resp = controller.ask(new AskRequest("  我的持仓怎么样  "));

        verify(client).ask("我的持仓怎么样");
        assertThat(resp).containsEntry("mode", "rule_based");
    }

    @Test
    void statusIsPassedThrough() {
        AssistantClient client = Mockito.mock(AssistantClient.class);
        when(client.status()).thenReturn(Map.of("available", true));
        AssistantController controller = new AssistantController(client);

        assertThat(controller.status()).containsEntry("available", true);
    }
}
