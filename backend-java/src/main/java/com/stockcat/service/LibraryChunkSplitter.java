package com.stockcat.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 把资料正文切成可检索、可引用的片段（P3.1）。
 *
 * <p>规则（来自 `docs/PLAN_P3.md` D4，经 P3.0 评估后微调）：
 * <ul>
 *   <li>优先在<b>段落边界</b>切分，合并到 300–600 字；单段超长时退到句子，仍超长才硬切。</li>
 *   <li>Markdown 标题（{@code #} 开头）作为片段的 {@code heading}，随后的段落继承它。</li>
 *   <li>相邻片段之间做<b>整段重叠</b>：把上一片段最后一段带到下一片段开头，避免答案落在切口上。
 *       不做"按字数截 80 字"的重叠——那会让 {@code charStart} 指向半个句子，引用就对不准了。</li>
 *   <li>片段永远由若干完整单元组成，{@code charStart}/{@code charEnd} 覆盖原文的连续区间。</li>
 * </ul>
 *
 * <p><b>偏移口径</b>：字符偏移是 Java 的 {@code char} 下标（UTF-16 code unit）。
 * 引用一律由 Java 端 {@code content.substring(start, end)} 取，不交给 SQL 的 {@code substring}，
 * 因此内部自洽；含 emoji 等代理对字符的正文偏移会与 PostgreSQL 的字符数不同，这是已知取舍。
 */
public final class LibraryChunkSplitter {

    /** 片段目标下限：达到这个长度就可以收尾 */
    static final int MIN_CHARS = 300;
    /** 片段硬上限 */
    static final int MAX_CHARS = 600;
    /** 跨片段重叠时，允许带入下一片段的单段最大长度 */
    static final int OVERLAP_MAX = 120;
    /** 句子切分符号 */
    private static final String SENTENCE_ENDS = "。！？；!?;";

    private LibraryChunkSplitter() {
    }

    /** 一个片段（尚未落库）。 */
    public record Chunk(int seq, String heading, String content, int charStart, int charEnd) {
    }

    /** 不可变的切分单元：一段（或一句、或硬切的一段）。 */
    private record Unit(int start, int end, String heading) {
        int length() {
            return end - start;
        }
    }

    public static List<Chunk> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Unit> units = toUnits(text);
        List<Chunk> chunks = new ArrayList<>();
        int seq = 1;
        int i = 0;
        while (i < units.size()) {
            int start = i;
            int end = i;
            int length = 0;
            while (end < units.size()) {
                int next = length + units.get(end).length();
                // 第一个单元无条件接受，保证循环一定前进（单元本身已 <= MAX_CHARS）
                if (end > start && next > MAX_CHARS) {
                    break;
                }
                length = next;
                end++;
                if (length >= MIN_CHARS) {
                    break;
                }
            }

            Unit first = units.get(start);
            Unit last = units.get(end - 1);
            chunks.add(new Chunk(seq++, first.heading(),
                    text.substring(first.start(), last.end()),
                    first.start(), last.end()));

            int nextStart = end;
            // 整段重叠：把本片段最后一段带到下一片段开头（单段过长则不带，避免下一片段一开头就超限）
            if (end < units.size() && (end - start) > 1
                    && units.get(end - 1).length() <= OVERLAP_MAX) {
                nextStart = end - 1;
            }
            // 防死循环：无论如何都要前进
            i = Math.max(nextStart, start + 1);
        }
        return chunks;
    }

    private static List<Unit> toUnits(String text) {
        List<Unit> units = new ArrayList<>();
        StringBuilder para = new StringBuilder();
        int paraStart = -1;
        String heading = null;

        int pos = 0;
        while (pos <= text.length()) {
            int lineEnd = text.indexOf('\n', pos);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(pos, lineEnd);

            if (isHeading(line)) {
                flushParagraph(text, para, paraStart, heading, units);
                para.setLength(0);
                paraStart = -1;
                heading = stripHeadingMark(line);
            } else if (line.isBlank()) {
                flushParagraph(text, para, paraStart, heading, units);
                para.setLength(0);
                paraStart = -1;
            } else {
                if (paraStart < 0) {
                    paraStart = pos;
                }
                if (para.length() > 0) {
                    para.append('\n');
                }
                para.append(line);
            }

            if (lineEnd >= text.length()) {
                break;
            }
            pos = lineEnd + 1;
        }
        flushParagraph(text, para, paraStart, heading, units);
        return units;
    }

    /**
     * 段落落成单元。超长时按句子切，单句仍超长则硬切——保证每个单元 <= MAX_CHARS，
     * 也保证偏移是原文的真实区间。
     */
    private static void flushParagraph(String text, StringBuilder para, int paraStart,
                                       String heading, List<Unit> out) {
        if (paraStart < 0 || para.length() == 0) {
            return;
        }
        int start = paraStart;
        int end = paraStart + para.length();
        if (end - start <= MAX_CHARS) {
            out.add(new Unit(start, end, heading));
            return;
        }

        int cursor = start;
        int sentenceStart = start;
        while (cursor < end) {
            char c = text.charAt(cursor);
            boolean atEnd = cursor == end - 1;
            if (SENTENCE_ENDS.indexOf(c) >= 0 || atEnd) {
                int stop = cursor + 1;
                addSliced(text, sentenceStart, stop, heading, out);
                sentenceStart = stop;
            }
            cursor++;
        }
    }

    /** 一句话可能仍超 MAX_CHARS（没有标点的长文），按长度硬切。 */
    private static void addSliced(String text, int start, int end, String heading, List<Unit> out) {
        int cursor = start;
        while (end - cursor > MAX_CHARS) {
            out.add(new Unit(cursor, cursor + MAX_CHARS, heading));
            cursor += MAX_CHARS;
        }
        if (end > cursor) {
            out.add(new Unit(cursor, end, heading));
        }
    }

    private static boolean isHeading(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '#') {
            i++;
        }
        return i > 0 && i <= 6 && i < line.length() && Character.isWhitespace(line.charAt(i));
    }

    private static String stripHeadingMark(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '#') {
            i++;
        }
        String rest = line.substring(i).trim();
        return rest.isEmpty() ? null : rest;
    }
}
