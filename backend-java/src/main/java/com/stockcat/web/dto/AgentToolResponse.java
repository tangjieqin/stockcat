package com.stockcat.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Agent 只读工具的统一包装（docs/PLAN_P2.md §2 P2.1）。
 *
 * <p>{@code dataAsOf} 是这次返回数据的<b>观测时点</b>（取数据内部最新时间戳），不是请求时间；
 * 前端与模型都必须用它来表述"数据截至何时"，不能用 now() 冒充。
 * {@code missing} 列出"本该有但没有"的东西，空列表表示数据完整——它是模型写"数据缺失"的唯一依据。
 *
 * @param tool    工具名，与请求路径一致，便于 Python 侧核对白名单
 * @param dataAsOf 数据观测时点；数据完全没有时间语义时为 null
 * @param data    工具数据
 * @param missing 缺失项的人类可读说明
 */
public record AgentToolResponse<T>(
        String tool,
        OffsetDateTime dataAsOf,
        T data,
        List<String> missing
) {
    public static <T> AgentToolResponse<T> of(String tool, OffsetDateTime dataAsOf, T data, List<String> missing) {
        return new AgentToolResponse<>(tool, dataAsOf, data, missing == null ? List.of() : List.copyOf(missing));
    }
}
