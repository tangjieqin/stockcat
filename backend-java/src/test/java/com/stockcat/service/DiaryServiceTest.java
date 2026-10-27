package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.DiaryNode;
import com.stockcat.mapper.DiaryNodeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 删除语义：DOC 报 1 篇，FOLDER 报子树里的文档数且必须 confirm。 */
@ExtendWith(MockitoExtension.class)
class DiaryServiceTest {

    @Mock
    private DiaryNodeMapper mapper;
    @InjectMocks
    private DiaryService service;

    @Test
    void deleteDocReportsOneDocument() {
        when(mapper.findById(9L)).thenReturn(node(9L, "DOC"));

        long deleted = service.delete(9L, false);

        // 删了一篇却说 0 篇，用户会以为没删掉
        assertThat(deleted).isEqualTo(1);
        verify(mapper).deleteSubtree(9L);
    }

    @Test
    void deleteFolderRequiresConfirm() {
        when(mapper.findById(3L)).thenReturn(node(3L, "FOLDER"));
        when(mapper.countDocsInSubtree(3L)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(3L, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("confirm=true");
        verify(mapper, never()).deleteSubtree(3L);
    }

    @Test
    void deleteFolderReportsDocsInSubtree() {
        when(mapper.findById(3L)).thenReturn(node(3L, "FOLDER"));
        when(mapper.countDocsInSubtree(3L)).thenReturn(4L);

        assertThat(service.delete(3L, true)).isEqualTo(4L);
        verify(mapper).deleteSubtree(3L);
    }

    private static DiaryNode node(Long id, String type) {
        DiaryNode n = new DiaryNode();
        n.setId(id);
        n.setNodeType(type);
        n.setTitle("节点" + id);
        return n;
    }
}
