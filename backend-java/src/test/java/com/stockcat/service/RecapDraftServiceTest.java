package com.stockcat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.assistant.AssistantClient;
import com.stockcat.common.ApiException;
import com.stockcat.common.ErrorCode;
import com.stockcat.domain.MarketRecap;
import com.stockcat.mapper.MarketRecapMapper;
import com.stockcat.web.dto.RecapDtos.SaveRecapDraftRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 复盘草稿的状态机与溯源信息：AI 永不覆盖 MANUAL，确认后才成正式复盘。
 */
class RecapDraftServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 27);
    private static final OffsetDateTime GENERATED = OffsetDateTime.of(2026, 10, 27, 7, 30, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime DATA_AS_OF = OffsetDateTime.of(2026, 10, 27, 7, 0, 0, 0, ZoneOffset.UTC);

    private MarketRecapMapper mapper;
    private AssistantClient client;
    private RecapDraftService service;

    @BeforeEach
    void setUp() {
        mapper = mock(MarketRecapMapper.class);
        client = mock(AssistantClient.class);
        service = new RecapDraftService(mapper, client, new ObjectMapper());
    }

    private static MarketRecap recap(String status) {
        MarketRecap recap = new MarketRecap();
        recap.setId(7L);
        recap.setRecapDate(DAY);
        recap.setTitle("周五复盘");
        recap.setContent("正文");
        recap.setStatus(status);
        return recap;
    }

    private static SaveRecapDraftRequest request(List<String> limitations) {
        return new SaveRecapDraftRequest(DAY, "2026-10-27 复盘（AI 草稿）", "指数快照",
                "自选分组", "# 正文", GENERATED, "deepseek-chat", DATA_AS_OF, limitations);
    }

    // ------------------------------------------------------------------ 生成

    @Test
    void generateRefusesWhenManualExists() {
        when(mapper.findByDate(DAY)).thenReturn(recap("MANUAL"));

        assertThatThrownBy(() -> service.generate(DAY))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("正式复盘");

        verifyNoInteractions(client);
    }

    @Test
    void generateAllowsRegeneratingExistingDraft() {
        when(mapper.findByDate(DAY)).thenReturn(recap("AI_DRAFT"));
        when(client.recapDraft("2026-10-27")).thenReturn(Map.of("mode", "llm"));

        assertThat(service.generate(DAY)).containsEntry("mode", "llm");
    }

    // ------------------------------------------------------------------ 保存

    @Test
    void saveInsertsDraftWithSourceInfo() {
        when(mapper.findByDate(DAY)).thenReturn(null);
        doAnswer(invocation -> {
            invocation.getArgument(0, MarketRecap.class).setId(11L);
            return 1;
        }).when(mapper).insert(any(MarketRecap.class));
        when(mapper.findById(11L)).thenReturn(recap("AI_DRAFT"));

        MarketRecap saved = service.saveDraft(request(List.of("指数是采集时点快照")));

        ArgumentCaptor<MarketRecap> captor = ArgumentCaptor.forClass(MarketRecap.class);
        verify(mapper).insert(captor.capture());
        MarketRecap inserted = captor.getValue();
        assertThat(inserted.getStatus()).isEqualTo("AI_DRAFT");
        assertThat(inserted.getAiGeneratedAt()).isEqualTo(GENERATED);
        assertThat(inserted.getAiModel()).isEqualTo("deepseek-chat");
        assertThat(inserted.getAiDataAsOf()).isEqualTo(DATA_AS_OF);
        assertThat(inserted.getAiLimitations()).contains("采集时点快照");
        assertThat(saved.getStatus()).isEqualTo("AI_DRAFT");
        // 新建不写 related_alert_event_ids：AI 草稿没有手工关联的告警
        assertThat(inserted.getRelatedAlertEventIds()).isNull();
    }

    @Test
    void saveOverwritesExistingDraftAndKeepsDraftStatus() {
        when(mapper.findByDate(DAY)).thenReturn(recap("AI_DRAFT"));
        when(mapper.updateDraft(eq(7L), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), anyString())).thenReturn(1);
        when(mapper.findById(7L)).thenReturn(recap("AI_DRAFT"));

        MarketRecap saved = service.saveDraft(request(List.of()));

        verify(mapper).updateDraft(eq(7L), eq("2026-10-27 复盘（AI 草稿）"), eq("指数快照"),
                eq("自选分组"), eq("# 正文"), eq(GENERATED), eq("deepseek-chat"), eq(DATA_AS_OF),
                eq("[]"));
        verify(mapper, never()).insert(any(MarketRecap.class));
        assertThat(saved.getStatus()).isEqualTo("AI_DRAFT");
    }

    @Test
    void saveRefusesManual() {
        when(mapper.findByDate(DAY)).thenReturn(recap("MANUAL"));

        assertThatThrownBy(() -> service.saveDraft(request(List.of())))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(mapper, never()).insert(any(MarketRecap.class));
        verify(mapper, never()).updateDraft(anyLong(), anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void saveFailsWhenDraftBecomesManualConcurrently() {
        when(mapper.findByDate(DAY)).thenReturn(recap("AI_DRAFT"));
        // 读到草稿后、写入前，用户点了保存把它变成 MANUAL：带 status 条件的 UPDATE 影响 0 行
        when(mapper.updateDraft(anyLong(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.saveDraft(request(List.of())))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void saveRejectsBlankContent() {
        assertThatThrownBy(() -> service.saveDraft(new SaveRecapDraftRequest(
                DAY, "标题", null, null, "   ", null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(mapper);
    }

    @Test
    void saveRejectsTitleOverColumnLimit() {
        String longTitle = "复".repeat(RecapDraftService.MAX_TITLE_CHARS + 1);

        assertThatThrownBy(() -> service.saveDraft(new SaveRecapDraftRequest(
                DAY, longTitle, null, null, "正文", null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("128");
    }
}
