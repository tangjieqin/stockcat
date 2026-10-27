package com.stockcat.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AssistantDtos {

    /** AI 助手提问。长度上限与 Python 侧 agent_question_max_chars 保持一致。 */
    public record AskRequest(
            @NotBlank(message = "question 不能为空")
            @Size(max = 500, message = "question 不能超过 500 字")
            String question
    ) {
    }
}
