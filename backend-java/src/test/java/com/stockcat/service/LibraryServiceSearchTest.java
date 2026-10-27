package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.common.ErrorCode;
import com.stockcat.domain.LibrarySearchRow;
import com.stockcat.mapper.LibraryChunkMapper;
import com.stockcat.mapper.LibraryDocumentMapper;
import com.stockcat.web.dto.LibraryDtos.SearchHit;
import com.stockcat.web.dto.LibraryDtos.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 资料检索（P3.2）：拆词交给 LibrarySearchTerms，这里管参数、截取与返回结构。 */
@ExtendWith(MockitoExtension.class)
class LibraryServiceSearchTest {

    @Mock
    private LibraryDocumentMapper documentMapper;
    @Mock
    private LibraryChunkMapper chunkMapper;
    @InjectMocks
    private LibraryService service;

    @Test
    void blankKeywordIsRejectedInsteadOfListingEverything() {
        assertThatThrownBy(() -> service.search("   ", null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessageContaining("请输入检索词");
    }

    @Test
    void searchPassesBigramPatternsToMapper() {
        when(chunkMapper.search(anyList(), anyList(), anyList(), anyInt(), anyInt(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.search("渠道库存", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> grams = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> rawGrams = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> wholes = ArgumentCaptor.forClass(List.class);
        verify(chunkMapper).search(grams.capture(), rawGrams.capture(), wholes.capture(),
                eq(LibrarySearchTerms.WHOLE_TERM_WEIGHT),
                eq(2), isNull(),
                eq(LibraryService.MAX_CHUNKS_PER_DOCUMENT),
                eq(LibraryService.DEFAULT_SEARCH_LIMIT));

        assertThat(grams.getValue()).containsExactly("%渠道%", "%道库%", "%库存%");
        assertThat(wholes.getValue()).containsExactly("%渠道库存%");
        // 排序用的裸词不能带 %：它会被塞进 position()/replace()，通配符在那边不是通配符而是字面量
        assertThat(rawGrams.getValue()).containsExactly("渠道", "道库", "库存");
    }

    /**
     * 2 字查询只切出 1 个 bigram，「命中 ≥2」对它是自相矛盾的门槛，必须传 0。
     * 传错的表现是搜"库存"永远返回空——比搜不准更糟。
     */
    @Test
    void twoCharacterQueryHasNoMinimumGramHits() {
        when(chunkMapper.search(anyList(), anyList(), anyList(), anyInt(), anyInt(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.search("库存", null, null);

        verify(chunkMapper).search(anyList(), anyList(), anyList(), anyInt(), eq(0), isNull(),
                anyInt(), anyInt());
    }

    @Test
    void everyTokenMustHitWhenQueryHasSeveralWords() {
        // 搜「黄金 ETF」时，一篇只讲 ETF 的笔记靠 ET/TF 就能进结果，而"黄金"一次都没出现
        List<LibrarySearchRow> rows = List.of(
                row("宽基 ETF 是我的底仓，占组合的 60%。"),
                row("黄金 ETF 的持仓变化反映了避险情绪。"));

        List<LibrarySearchRow> kept = LibraryService.requireEveryTokenHit(rows, "黄金 ETF");

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).getContent()).contains("黄金");
    }

    @Test
    void singleTokenQuerySkipsTokenCoverageFilter() {
        // 「茅台批价」在中文里没有空格，是一个 token：要求"茅台"和"批价"必须同段出现，
        // 会把原文里分处两段的正确结果判成无关
        List<LibrarySearchRow> rows = List.of(row("只讲批价的段落，没提那个牌子"));

        assertThat(LibraryService.requireEveryTokenHit(rows, "茅台批价")).hasSize(1);
    }

    @Test
    void limitIsCappedSoOneQueryCannotPullTheWholeLibrary() {
        when(chunkMapper.search(anyList(), anyList(), anyList(), anyInt(), anyInt(), anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.search("白酒", 9999, 7L);

        verify(chunkMapper).search(anyList(), anyList(), anyList(), anyInt(), anyInt(), eq(7L), anyInt(),
                eq(LibraryService.MAX_SEARCH_LIMIT));
    }

    @Test
    void hitCarriesSnippetAndLocator() {
        LibrarySearchRow row = row("我们走访了华东六个城市的经销商，渠道库存周转天数从 42 天降至 35 天，但绝对水位仍高于去年同期。");
        when(chunkMapper.search(anyList(), anyList(), anyList(), anyInt(), anyInt(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(row));

        SearchResult result = service.search("渠道库存", null, null);

        assertThat(result.hits()).hasSize(1);
        var hit = result.hits().get(0);
        assertThat(hit.chunkId()).isEqualTo(11L);
        assertThat(hit.documentId()).isEqualTo(3L);
        assertThat(hit.documentTitle()).isEqualTo("白酒渠道调研纪要");
        assertThat(hit.seq()).isEqualTo(2);
        assertThat(hit.snippet()).contains("渠道库存");
        // 定位信息要在，否则前端点了只能重新搜一遍
        assertThat(hit.charStart()).isEqualTo(120);
        assertThat(hit.charEnd()).isEqualTo(180);
        assertThat(hit.publishedAt()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    void snippetAddsEllipsisOnBothSidesWhenHitIsInTheMiddle() {
        String body = "前".repeat(200) + "渠道库存" + "后".repeat(200);

        String snippet = LibraryService.snippet(body, List.of("渠道库存"));

        assertThat(snippet).startsWith("…").endsWith("…").contains("渠道库存");
        assertThat(snippet.length()).isLessThan(body.length());
    }

    @Test
    void snippetHasNoLeadingEllipsisWhenHitIsAtTheStart() {
        String body = "渠道库存周转天数下降。" + "后".repeat(300);

        String snippet = LibraryService.snippet(body, List.of("渠道库存"));

        assertThat(snippet).startsWith("渠道库存").endsWith("…");
    }

    @Test
    void snippetCollapsesNewlinesSoListStaysSingleLine() {
        String body = "第一段\n\n渠道库存\n周转天数下降。";

        String snippet = LibraryService.snippet(body, List.of("渠道库存"));

        assertThat(snippet).doesNotContain("\n").contains("渠道库存");
    }

    @Test
    void matchedTermsDropFragmentsCoveredByWholeTerm() {
        // 整词命中时连 60/00/05 一起返回，高亮会把代码标成一串色块
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of("600519");

        assertThat(LibraryService.matchedTerms("贵州茅台（600519）三季报", terms))
                .containsExactly("600519");
    }

    @Test
    void matchedTermsFallBackToFragmentsWhenWholeTermIsMissing() {
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of("渠道库存");

        assertThat(LibraryService.matchedTerms("只有库存没有别的", terms))
                .containsExactly("库存");
    }

    @Test
    void titleOnlyHitsAreThinnedToOnePerDocument() {
        // 搜"调研纪要"时标题命中会让同一篇的每一段都得分，全返回就是三行同样的证据
        List<SearchHit> hits = LibraryService.thinTitleOnlyHits(List.of(
                hit(6L, 1, List.of()),
                hit(6L, 2, List.of()),
                hit(6L, 3, List.of()),
                hit(3L, 1, List.of())));

        assertThat(hits).extracting(SearchHit::documentId).containsExactly(6L, 3L);
    }

    @Test
    void titleOnlyHitsAreDroppedWhenSameDocumentHasRealContentHit() {
        List<SearchHit> hits = LibraryService.thinTitleOnlyHits(List.of(
                hit(6L, 1, List.of()),
                hit(6L, 2, List.of("渠道库存")),
                hit(9L, 1, List.of())));

        assertThat(hits).extracting(SearchHit::seq).containsExactly(2, 1);
        assertThat(hits).extracting(SearchHit::documentId).containsExactly(6L, 9L);
    }

    private static SearchHit hit(Long documentId, int seq, List<String> matched) {
        return new SearchHit(1L, documentId, "标题", seq, null, "片段", 0, 10,
                "PASTE", null, null, null, null, 1, matched, matched.isEmpty());
    }

    private static LibrarySearchRow row(String content) {
        LibrarySearchRow row = new LibrarySearchRow();
        row.setChunkId(11L);
        row.setDocumentId(3L);
        row.setSeq(2);
        row.setHeading("渠道跟踪");
        row.setContent(content);
        row.setCharStart(120);
        row.setCharEnd(180);
        row.setDocumentTitle("白酒渠道调研纪要");
        row.setSourceType("PASTE");
        row.setSourceRef(null);
        row.setPublisher("某券商");
        row.setPublishedAt(LocalDate.of(2026, 9, 20));
        row.setScore(7);
        return row;
    }
}
