package com.stockcat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.domain.MarketRecap;
import com.stockcat.service.RecapDraftService;
import com.stockcat.service.RecapService;
import com.stockcat.web.dto.RecapDtos.SaveRecapDraftRequest;
import com.stockcat.web.dto.RecapResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复盘 AI 两个入口：生成只转发日期，保存把 JSONB 溯源信息还原成 JSON 返回。
 */
class RecapControllerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 27);

    @Test
    void generatePassesDateToService() {
        RecapDraftService draftService = Mockito.mock(RecapDraftService.class);
        when(draftService.generate(DAY)).thenReturn(Map.of("mode", "rule_based"));
        RecapController controller = new RecapController(Mockito.mock(RecapService.class), draftService,
                new ObjectMapper());

        Map<String, Object> resp = controller.generateAiDraft(
                new com.stockcat.web.dto.RecapDtos.AiDraftRequest(DAY));

        verify(draftService).generate(DAY);
        assertThat(resp).containsEntry("mode", "rule_based");
    }

    @Test
    void saveReturnsAiSourceFields() {
        RecapDraftService draftService = Mockito.mock(RecapDraftService.class);
        MarketRecap saved = new MarketRecap();
        saved.setId(9L);
        saved.setRecapDate(DAY);
        saved.setTitle("2026-10-27 复盘（AI 草稿）");
        saved.setContent("# 正文");
        saved.setStatus("AI_DRAFT");
        saved.setAiGeneratedAt(OffsetDateTime.of(2026, 10, 27, 7, 30, 0, 0, ZoneOffset.UTC));
        saved.setAiModel("deepseek-chat");
        saved.setAiLimitations("[\"指数是采集时点快照\"]");
        SaveRecapDraftRequest req = new SaveRecapDraftRequest(DAY, saved.getTitle(), null, null,
                "# 正文", saved.getAiGeneratedAt(), "deepseek-chat", null, List.of("指数是采集时点快照"));
        when(draftService.saveDraft(req)).thenReturn(saved);
        RecapController controller = new RecapController(Mockito.mock(RecapService.class), draftService,
                new ObjectMapper());

        RecapResponse resp = controller.saveDraft(req);

        assertThat(resp.status()).isEqualTo("AI_DRAFT");
        assertThat(resp.aiModel()).isEqualTo("deepseek-chat");
        assertThat(resp.aiLimitations().toString()).contains("采集时点快照");
    }
}
