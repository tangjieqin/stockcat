package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.LibraryChunk;
import com.stockcat.domain.LibraryDocument;
import com.stockcat.mapper.LibraryChunkMapper;
import com.stockcat.mapper.LibraryDocumentMapper;
import com.stockcat.web.dto.LibraryDtos.CreateDocumentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibraryServiceTest {

    @Mock
    private LibraryDocumentMapper documentMapper;
    @Mock
    private LibraryChunkMapper chunkMapper;
    @InjectMocks
    private LibraryService service;

    private static final String BODY = "今天白酒继续跌，茅台跌了 2%，我把仓位从三成降到两成。".repeat(12);

    @Test
    void duplicateContentIsRejectedWithExistingId() {
        when(documentMapper.findIdByHash(any())).thenReturn(42L);

        assertThatThrownBy(() -> service.create(req("标题", BODY)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("id=42");
        // 重复导入不能落库，也不能白切一遍片段
        verify(documentMapper, never()).insert(any());
        verify(chunkMapper, never()).batchInsert(anyList());
    }

    @Test
    void blankContentIsRejected() {
        assertThatThrownBy(() -> service.create(req("标题", "   \n\n  ")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("正文为空");
    }

    @Test
    void tooLongContentIsRejected() {
        String huge = "字".repeat(LibraryService.MAX_CONTENT_CHARS + 1);

        assertThatThrownBy(() -> service.create(req("标题", huge)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("超过");
    }

    @Test
    void urlSourceTypeIsRejectedForNow() {
        CreateDocumentRequest request = new CreateDocumentRequest(
                "标题", "URL", "https://example.com", null, null, BODY, null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PASTE 或 FILE");
    }

    @Test
    void createStoresDocumentAndChunksTogether() {
        when(documentMapper.findIdByHash(any())).thenReturn(null);
        // 模拟 useGeneratedKeys 回填主键
        doAnswer(inv -> {
            ((LibraryDocument) inv.getArgument(0)).setId(1L);
            return 1;
        }).when(documentMapper).insert(any());
        when(documentMapper.findById(any())).thenAnswer(inv -> stored);
        when(chunkMapper.selectByDocumentId(any())).thenReturn(List.of());
        when(documentMapper.selectInstrumentIds(any())).thenReturn(List.of());

        service.create(req("白酒跟踪", BODY));

        ArgumentCaptor<LibraryDocument> docCaptor = ArgumentCaptor.forClass(LibraryDocument.class);
        verify(documentMapper).insert(docCaptor.capture());
        LibraryDocument saved = docCaptor.getValue();
        assertThat(saved.getTitle()).isEqualTo("白酒跟踪");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getCharCount()).isEqualTo(saved.getContent().length());
        assertThat(saved.getContentHash()).hasSize(64);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LibraryChunk>> chunkCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkMapper).batchInsert(chunkCaptor.capture());
        List<LibraryChunk> chunks = chunkCaptor.getValue();
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getSeq()).isEqualTo(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.getDocumentId()).isEqualTo(1L));
        // 片段内容必须能在正文里原样找到，否则引用就不可信
        assertThat(chunks).allSatisfy(c ->
                assertThat(saved.getContent().substring(c.getCharStart(), c.getCharEnd()))
                        .isEqualTo(c.getContent()));
    }

    @Test
    void deleteRequiresConfirmAndReportsChunkCount() {
        when(documentMapper.findById(7L)).thenReturn(stored);
        when(chunkMapper.countByDocumentId(7L)).thenReturn(5);

        assertThatThrownBy(() -> service.delete(7L, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("5 个片段");
        verify(documentMapper, never()).deleteById(anyLong());
    }

    @Test
    void deleteCascadesChunksByForeignKey() {
        when(documentMapper.findById(7L)).thenReturn(stored);
        when(chunkMapper.countByDocumentId(7L)).thenReturn(5);

        assertThat(service.delete(7L, true)).isEqualTo(5);
        verify(documentMapper).deleteById(eq(7L));
        // 片段由 ON DELETE CASCADE 清理，Service 不该再手工删一遍
        verify(chunkMapper, never()).batchInsert(anyList());
    }

    @Test
    void normalizeUnifiesLineEndingsAndCollapsesBlankRuns() {
        String raw = "\uFEFF第一段  \r\n\r\n\r\n\r\n第二段\t\r\n";

        assertThat(LibraryService.normalize(raw)).isEqualTo("第一段\n\n第二段");
    }

    @Test
    void normalizeIsStableSoHashCatchesSameTextDifferentLayout() {
        String a = "第一段\r\n\r\n第二段";
        String b = "\uFEFF第一段   \n\n\n\n第二段\n";

        assertThat(LibraryService.normalize(a)).isEqualTo(LibraryService.normalize(b));
        assertThat(LibraryService.sha256(LibraryService.normalize(a)))
                .isEqualTo(LibraryService.sha256(LibraryService.normalize(b)));
    }

    private static final LibraryDocument stored = buildStored();

    private static LibraryDocument buildStored() {
        LibraryDocument d = new LibraryDocument();
        d.setId(1L);
        d.setTitle("白酒跟踪");
        d.setContent(BODY);
        return d;
    }

    private static CreateDocumentRequest req(String title, String content) {
        return new CreateDocumentRequest(title, "PASTE", null, null, null, content, null, null);
    }
}
