package com.stockcat.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "AI 只能读"这条红线靠结构锁死，不靠人记得：给 AgentToolController 加写接口会直接挂测试。
 */
class AgentToolControllerTest {

    private static final Set<String> WHITELIST = Set.of(
            "/watchlist-quotes", "/positions", "/alert-events",
            "/market-brief", "/recaps", "/diary/search");

    @Test
    void basePathIsTheAgentToolsPrefix() {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                AgentToolController.class, RequestMapping.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.path()).containsExactly("/api/v1/agent/tools");
    }

    @Test
    void everyHandlerIsGetOnly() {
        for (Method method : AgentToolController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            assertThat(mapping.method())
                    .as("%s 必须是 GET：Agent 工具不允许任何写操作", method.getName())
                    .containsExactly(RequestMethod.GET);
        }
    }

    @Test
    void exposesExactlyTheWhitelistedTools() {
        Set<String> paths = new TreeSet<>();
        for (Method method : AgentToolController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping != null) {
                paths.addAll(Arrays.asList(mapping.path()));
            }
        }

        // 新增工具必须先改白名单，P2.2 的 Python 侧也同步维护同一份清单
        assertThat(paths).containsExactlyInAnyOrderElementsOf(WHITELIST);
    }
}
