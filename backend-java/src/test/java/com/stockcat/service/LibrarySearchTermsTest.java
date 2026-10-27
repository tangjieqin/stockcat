package com.stockcat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 检索词拆分（P3.2）：拆错一个词，后面的排序与高亮全错。 */
class LibrarySearchTermsTest {

    @Test
    void fourCharTermSplitsIntoThreeBigrams() {
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of("渠道库存");

        assertThat(terms.grams()).containsExactly("渠道", "道库", "库存");
        assertThat(terms.wholes()).containsExactly("渠道库存");
    }

    @Test
    void twoCharTermStaysWhole() {
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of("茅台");

        assertThat(terms.grams()).containsExactly("茅台");
        assertThat(terms.wholes()).containsExactly("茅台");
    }

    @Test
    void segmentsAreSplitBeforeBigramSoNoCrossWordGrams() {
        // "白酒库存" 直接 bigram 会产生"酒库"——两个词拼出来的碎片，只会带来噪声
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of("白酒 库存");

        assertThat(terms.grams()).containsExactly("白酒", "库存");
        assertThat(terms.grams()).doesNotContain("酒库");
    }

    @Test
    void punctuationSplitsTokens() {
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of("渠道，库存（白酒）");

        assertThat(terms.wholes()).containsExactly("渠道", "库存", "白酒");
    }

    @Test
    void singleCharQueryFallsBackToItself() {
        // 单字直接 bigram 会召回整库，所以平时不进 grams；但整条查询只有一个字时不能返回空
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of("茅");

        assertThat(terms.grams()).containsExactly("茅");
        assertThat(terms.wholes()).isEmpty();
    }

    @Test
    void singleCharInsideLongerQueryIsDropped() {
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of("白酒 的 库存");

        assertThat(terms.grams()).containsExactly("白酒", "库存");
        assertThat(terms.wholes()).containsExactly("白酒", "库存");
    }

    @Test
    void fullWidthDigitsAreNormalized() {
        // 从 PDF/Excel 复制来的"６００５１９"是 U+FF10 系列，不转就永远匹配不上库里的半角代码
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of("６００５１９");

        assertThat(terms.grams()).contains("60", "00", "05", "51", "19");
        assertThat(terms.wholes()).containsExactly("600519");
    }

    @Test
    void likePatternEscapesWildcards() {
        assertThat(LibrarySearchTerms.likePattern("50%")).isEqualTo("%50\\%%");
        assertThat(LibrarySearchTerms.likePattern("a_b")).isEqualTo("%a\\_b%");
        assertThat(LibrarySearchTerms.likePattern("c\\d")).isEqualTo("%c\\\\d%");
        assertThat(LibrarySearchTerms.likePattern("渠道")).isEqualTo("%渠道%");
    }

    @Test
    void longSentenceIsCapped() {
        // 用互不相同的汉字，才能保证 bigram 不因去重而变少，测到的是"截断"本身
        StringBuilder sb = new StringBuilder();
        for (char c = 0x4E00; c < 0x4E00 + 200; c++) {
            sb.append(c);
        }
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of(sb.toString());

        assertThat(terms.grams()).hasSize(LibrarySearchTerms.MAX_GRAMS);
        assertThat(terms.wholes()).hasSize(1);
    }

    @Test
    void repeatedTextDoesNotMultiplyGrams() {
        // 重复的 bigram 若不去重会重复加分，得分就不再反映"命中多少内容"
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of("渠道库存".repeat(3));

        assertThat(terms.grams()).hasSizeLessThanOrEqualTo(LibrarySearchTerms.MAX_GRAMS);
        assertThat(terms.grams()).doesNotHaveDuplicates();
    }

    @Test
    void blankQueryHasNoTerms() {
        assertThat(LibrarySearchTerms.of("   ").isEmpty()).isTrue();
        assertThat(LibrarySearchTerms.of(null).isEmpty()).isTrue();
        assertThat(LibrarySearchTerms.of("，。；").isEmpty()).isTrue();
    }

    @Test
    void gramsAreDeduplicated() {
        // 重复的 bigram 会重复加分，让得分失去意义
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of("库存库存");

        assertThat(terms.grams()).containsExactly("库存", "存库");
    }

    @Test
    void tokensAreDeduplicated() {
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of("白酒 白酒");

        assertThat(terms.wholes()).isEqualTo(List.of("白酒"));
    }

    @Test
    void minGramHitsIsTwoExceptForSingleGramQueries() {
        // 2 字查询只切出 1 个 bigram，门槛必须是 0，否则"库存"永远搜不到东西
        assertThat(LibrarySearchTerms.minGramHits(1)).isZero();
        // 不按比例上浮：30% 门槛实测把「净息差还能不能稳住」打到 0 召回（见方法注释）
        assertThat(LibrarySearchTerms.minGramHits(3)).isEqualTo(2);
        assertThat(LibrarySearchTerms.minGramHits(7)).isEqualTo(2);
        assertThat(LibrarySearchTerms.minGramHits(13)).isEqualTo(2);
    }

    @Test
    void gramGroupsKeepTokensApart() {
        // "黄金 ETF" 必须分成两组：合成一组会让"接口"这种跨词碎片凭空冒出来
        assertThat(LibrarySearchTerms.gramGroups("黄金 ETF"))
                .containsExactly(List.of("黄金"), List.of("ET", "TF"));
    }

    @Test
    void gramGroupsKeepSingleCharacterToken() {
        // 单字 token 保留自身，否则"茅台 批"这种查询里"批"会被静默丢掉
        assertThat(LibrarySearchTerms.gramGroups("茅台 批"))
                .containsExactly(List.of("茅台"), List.of("批"));
    }
}
