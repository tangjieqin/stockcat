package com.stockcat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切片器的核心不变量：
 * 1. 每个片段的 content 必须等于原文对应区间的 substring——引用可追溯全靠这条；
 * 2. 片段首尾覆盖全文，不留空洞；
 * 3. 任何输入都不能死循环（含超长无标点、单行、空行）。
 */
class LibraryChunkSplitterTest {

    @Test
    void emptyOrBlankReturnsNoChunk() {
        assertThat(LibraryChunkSplitter.split(null)).isEmpty();
        assertThat(LibraryChunkSplitter.split("")).isEmpty();
        assertThat(LibraryChunkSplitter.split("   \n\n  ")).isEmpty();
    }

    @Test
    void shortTextBecomesOneChunkCoveringWholeText() {
        String text = "今天白酒继续跌，茅台跌了 2%，我把仓位从三成降到两成。";

        List<LibraryChunkSplitter.Chunk> chunks = LibraryChunkSplitter.split(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo(text);
        assertThat(chunks.get(0).charStart()).isZero();
        assertThat(chunks.get(0).charEnd()).isEqualTo(text.length());
        assertThat(chunks.get(0).seq()).isEqualTo(1);
    }

    @Test
    void marksdownHeadingBecomesChunkHeading() {
        // 两章都要够长，否则会被合并成一个片段——那样 heading 只能取第一个
        String body1 = "经销商反馈库存周转天数下降，但绝对水位仍高于去年。".repeat(12);
        String body2 = "一批价在国庆后回落约 30 元，厂家发货节奏前置。".repeat(12);
        String text = """
                # 渠道跟踪

                %s

                ## 批价

                %s
                """.formatted(body1, body2);

        List<LibraryChunkSplitter.Chunk> chunks = LibraryChunkSplitter.split(text);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.get(0).heading()).isEqualTo("渠道跟踪");
        assertThat(chunks.get(chunks.size() - 1).heading()).isEqualTo("批价");
        // 标题行本身属于它下面的段落，不应单独成片段
        assertThat(chunks).noneMatch(c -> c.content().startsWith("#"));
    }

    @Test
    void shortTextWithHeadingKeepsFirstHeading() {
        String text = """
                # 渠道跟踪

                很短的一段。
                """;

        List<LibraryChunkSplitter.Chunk> chunks = LibraryChunkSplitter.split(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).heading()).isEqualTo("渠道跟踪");
    }

    @Test
    void longTextIsSplitAndEveryChunkMatchesOriginalSlice() {
        String text = paragraphs(12, "这一段用来测试切片，内容重复但不影响偏移的正确性。");

        List<LibraryChunkSplitter.Chunk> chunks = LibraryChunkSplitter.split(text);

        assertThat(chunks.size()).isGreaterThan(1);
        for (LibraryChunkSplitter.Chunk c : chunks) {
            // 引用能不能回到原文，就看这一条
            assertThat(text.substring(c.charStart(), c.charEnd())).isEqualTo(c.content());
            assertThat(c.content().length()).isLessThanOrEqualTo(LibraryChunkSplitter.MAX_CHARS);
            assertThat(c.charStart()).isLessThan(c.charEnd());
        }
        // 首尾覆盖全文
        assertThat(chunks.get(0).charStart()).isZero();
        assertThat(chunks.get(chunks.size() - 1).charEnd()).isEqualTo(text.length());
        // 序号递增
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).seq()).isEqualTo(i + 1);
        }
    }

    @Test
    void adjacentChunksOverlapByOneParagraph() {
        String text = paragraphs(12, "同一段文字反复出现，用于验证相邻片段之间确实带了上一段的尾巴。");

        List<LibraryChunkSplitter.Chunk> chunks = LibraryChunkSplitter.split(text);

        assertThat(chunks.size()).isGreaterThan(1);
        // 重叠是整段带入：后一片段的起点应早于前一片段的终点
        assertThat(chunks.get(1).charStart()).isLessThan(chunks.get(0).charEnd());
    }

    @Test
    void veryLongParagraphWithoutPunctuationIsHardSplit() {
        String text = "啊".repeat(2000);

        List<LibraryChunkSplitter.Chunk> chunks = LibraryChunkSplitter.split(text);

        assertThat(chunks).isNotEmpty();
        for (LibraryChunkSplitter.Chunk c : chunks) {
            assertThat(c.content().length()).isLessThanOrEqualTo(LibraryChunkSplitter.MAX_CHARS);
            assertThat(text.substring(c.charStart(), c.charEnd())).isEqualTo(c.content());
        }
        assertThat(chunks.get(chunks.size() - 1).charEnd()).isEqualTo(text.length());
    }

    @Test
    void longParagraphIsSplitBySentence() {
        String sentence = "这是一句用来测试句子边界的话，长度大约三十多个字符左右。";
        String text = sentence.repeat(40);

        List<LibraryChunkSplitter.Chunk> chunks = LibraryChunkSplitter.split(text);

        assertThat(chunks.size()).isGreaterThan(1);
        for (LibraryChunkSplitter.Chunk c : chunks) {
            assertThat(c.content().length()).isLessThanOrEqualTo(LibraryChunkSplitter.MAX_CHARS);
            assertThat(text.substring(c.charStart(), c.charEnd())).isEqualTo(c.content());
        }
    }

    @Test
    void singleLineWithoutNewlineIsHandled() {
        String text = "只有一行没有任何换行的文本，用来确认扫描循环不会越界。";

        List<LibraryChunkSplitter.Chunk> chunks = LibraryChunkSplitter.split(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo(text);
    }

    private static String paragraphs(int count, String body) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("第 ").append(i + 1).append(" 段：").append(body);
            if (i < count - 1) {
                sb.append("\n\n");
            }
        }
        return sb.toString();
    }
}
