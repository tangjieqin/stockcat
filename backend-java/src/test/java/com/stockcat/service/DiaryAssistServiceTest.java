package com.stockcat.service;

import com.stockcat.assistant.AssistantClient;
import com.stockcat.common.ApiException;
import com.stockcat.domain.DiaryNode;
import com.stockcat.web.dto.InstrumentResponse;
import com.stockcat.web.dto.PositionResponse;
import com.stockcat.web.dto.WatchlistItemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiaryAssistServiceTest {

    @Mock
    private WatchlistService watchlistService;
    @Mock
    private PositionService positionService;
    @Mock
    private DiaryService diaryService;
    @Mock
    private AssistantClient assistantClient;

    private DiaryAssistService service;

    @BeforeEach
    void setup() {
        service = new DiaryAssistService(watchlistService, positionService, diaryService, assistantClient);
    }

    @Test
    void knownInstrumentsMergesWatchlistAndPositionsWithoutDuplicates() {
        when(watchlistService.list()).thenReturn(List.of(
                watchItem("510300", "沪深300ETF"),
                watchItem("600519", "贵州茅台")));
        when(positionService.list()).thenReturn(List.of(
                position("510300", "沪深300ETF"),
                position("000001", "平安银行")));

        List<Map<String, Object>> known = service.knownInstruments();

        // 自选优先占据注意力，持仓补充；510300 两边都有，只出现一次
        assertThat(known).extracting(m -> m.get("symbol"))
                .containsExactly("510300", "600519", "000001");
        assertThat(known.get(0)).containsEntry("name", "沪深300ETF");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refinePrefersEditorContentOverStoredNode() {
        DiaryNode node = node(5L, "周记", "库里保存的旧正文，够长了吧");
        when(diaryService.get(5L)).thenReturn(node);
        when(watchlistService.list()).thenReturn(List.of(watchItem("510300", "沪深300ETF")));
        when(positionService.list()).thenReturn(List.of());
        when(assistantClient.refineDiary(anyMap())).thenReturn(Map.of("mode", "rule_based"));

        // 标题留空、正文是编辑器里的新版本：整理眼前的这一版，而不是库里的旧版
        service.refine(5L, null, "编辑器里刚写的新正文，还没保存");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(assistantClient).refineDiary(captor.capture());
        Map<String, Object> payload = captor.getValue();

        assertThat(payload).containsEntry("content", "编辑器里刚写的新正文，还没保存");
        assertThat(payload).containsEntry("title", "周记");
        assertThat((List<Map<String, Object>>) payload.get("knownInstruments")).hasSize(1);
    }

    @Test
    void refineFallsBackToStoredContentWhenEditorIsEmpty() {
        DiaryNode node = node(5L, "周记", "库里的正文内容，长度够了");
        when(diaryService.get(5L)).thenReturn(node);
        when(watchlistService.list()).thenReturn(List.of());
        when(positionService.list()).thenReturn(List.of());
        when(assistantClient.refineDiary(any())).thenReturn(Map.of());

        service.refine(5L, null, "   ");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(assistantClient).refineDiary(captor.capture());
        assertThat(captor.getValue()).containsEntry("content", "库里的正文内容，长度够了");
    }

    @Test
    void refineRejectsEmptyBody() {
        assertThatThrownBy(() -> service.refine(null, "空标题", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("正文为空");
    }

    @Test
    void refineRejectsOverlongBodyInsteadOfSilentlyTruncating() {
        String huge = "字".repeat(20_001);

        // 悄悄截断会让用户以为整篇都整理过了，不如直接说"太长了"
        assertThatThrownBy(() -> service.refine(null, "长文", huge))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("超过");
    }

    @Test
    void reviewRequiresKeyword() {
        assertThatThrownBy(() -> service.review("  ", 5))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("keyword");
    }

    @Test
    void reviewClampsLimitToServerMaximum() {
        when(assistantClient.reviewDiary(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Map.of("hitCount", 0, "empty", true));

        service.review("减仓", 99);
        service.review("减仓", null);

        org.mockito.Mockito.verify(assistantClient).reviewDiary("减仓", 10);
        org.mockito.Mockito.verify(assistantClient).reviewDiary("减仓", 5);
    }

    // ------------------------------------------------------------------ 夹具

    private static WatchlistItemResponse watchItem(String symbol, String name) {
        return new WatchlistItemResponse(1L,
                new InstrumentResponse(1L, "CN", symbol, name, "ETF", "CNY"),
                null, null, null);
    }

    private static PositionResponse position(String symbol, String name) {
        return new PositionResponse(1L, symbol, name, null, null, null, null,
                null, null, null, "VALID");
    }

    private static DiaryNode node(Long id, String title, String content) {
        DiaryNode node = new DiaryNode();
        node.setId(id);
        node.setTitle(title);
        node.setContent(content);
        node.setNodeType("DOC");
        return node;
    }
}
