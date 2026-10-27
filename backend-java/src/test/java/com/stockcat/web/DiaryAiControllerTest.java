package com.stockcat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.service.DiaryAssistService;
import com.stockcat.service.DiaryService;
import com.stockcat.web.dto.RecapDtos.DiaryRefineRequest;
import com.stockcat.web.dto.RecapDtos.DiaryReviewRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日记 AI 两个入口：只转发参数，不在这里做业务判断。
 */
class DiaryAiControllerTest {

    private DiaryController controller(DiaryAssistService assist) {
        return new DiaryController(Mockito.mock(DiaryService.class), assist, new ObjectMapper());
    }

    @Test
    void refinePassesNodeIdAndEditorContent() {
        DiaryAssistService assist = Mockito.mock(DiaryAssistService.class);
        when(assist.refine(5L, "周记", "正文"))
                .thenReturn(Map.of("mode", "rule_based"));

        Map<String, Object> resp = controller(assist)
                .aiRefine(new DiaryRefineRequest(5L, "周记", "正文"));

        verify(assist).refine(5L, "周记", "正文");
        assertThat(resp).containsEntry("mode", "rule_based");
    }

    @Test
    void reviewPassesKeywordAndLimit() {
        DiaryAssistService assist = Mockito.mock(DiaryAssistService.class);
        when(assist.review("减仓", 3))
                .thenReturn(Map.of("hitCount", 0, "empty", true));

        Map<String, Object> resp = controller(assist)
                .aiReview(new DiaryReviewRequest("减仓", 3));

        verify(assist).review("减仓", 3);
        assertThat(resp).containsEntry("empty", true);
    }

    @Test
    void reviewAllowsMissingLimit() {
        DiaryAssistService assist = Mockito.mock(DiaryAssistService.class);
        when(assist.review("减仓", null)).thenReturn(Map.of("hitCount", 1));

        controller(assist).aiReview(new DiaryReviewRequest("减仓", null));

        verify(assist).review("减仓", null);
    }
}
