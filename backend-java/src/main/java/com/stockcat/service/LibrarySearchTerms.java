package com.stockcat.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把用户输入的关键词拆成检索单元（P3.2）。
 *
 * <p>为什么是 bigram：中文没有空格，而 PostgreSQL 的 {@code simple} 分词对中文等于不切词，
 * {@code zhparser}/{@code pg_jieba} 要源码编译。bigram 零依赖，P3.0 实测 recall@5 0.974
 * （整词 ILIKE 0.737），代价是 precision 0.631 偏低——见 {@code docs/PLAN_P3_EVAL.md}。
 *
 * <p>几条刻意的选择：
 * <ul>
 *   <li><b>按标点/空白切 token，再各自 bigram</b>。"白酒 库存"若直接去掉空格拼成
 *       "白酒库存"，会生成"酒库"这种跨越两个词的碎片，凭空噪声。</li>
 *   <li><b>单字不进 bigram</b>。"的"、"了"这种单字命中率极高，会把整个库召回。只有当
 *       整条查询只有一个字时，才退化用这个字兜底——否则搜"茅"会直接返回空，比噪声更糟。</li>
 *   <li><b>不过滤停用词</b>。P3.0 实测加了停用词表 precision 只从 0.631 到 0.639，
 *       不值得为此维护一张词表。</li>
 *   <li><b>全角转半角</b>。用户从 PDF 或 Excel 复制来的"６００５１９"是 U+FF10 系列，
 *       不转换就永远匹配不上库里的半角代码。</li>
 * </ul>
 */
public final class LibrarySearchTerms {

    /** 一条查询最多拆这么多个词：长句会生成上百个 bigram，SQL 会变成噪声炸弹。 */
    static final int MAX_TOKENS = 12;
    /** bigram 总数上限，超出按出现顺序截断。 */
    static final int MAX_GRAMS = 40;
    /** 整词命中权重：整词比碎片更有价值，权重 3 沿用 P3.0 评估口径。 */
    static final int WHOLE_TERM_WEIGHT = 3;

    private LibrarySearchTerms() {
    }

    /**
     * @param wholes 用于加分的整词（长度 ≥ 2 的 token）；单字查询时为空
     * @param grams  用于召回的 bigram，已去重保序
     */
    public record Terms(List<String> wholes, List<String> grams) {

        public boolean isEmpty() {
            return grams.isEmpty();
        }
    }

    public static Terms of(String keyword) {
        List<String> tokens = tokenize(keyword);
        if (tokens.isEmpty()) {
            return new Terms(List.of(), List.of());
        }

        Set<String> grams = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token.length() == 2) {
                grams.add(token);
            } else if (token.length() > 2) {
                for (int i = 0; i + 2 <= token.length(); i++) {
                    grams.add(token.substring(i, i + 2));
                }
            }
            if (grams.size() >= MAX_GRAMS) {
                break;
            }
        }
        if (grams.isEmpty()) {
            // 只有单字：退化用单字兜底，宁可召回宽也不要搜出空结果
            grams.add(tokens.get(0));
        }

        List<String> wholes = tokens.stream().filter(t -> t.length() >= 2).toList();
        return new Terms(wholes, List.copyOf(grams).subList(0, Math.min(grams.size(), MAX_GRAMS)));
    }

    /**
     * 每个 token 各自的 bigram，用于"多词查询里每个词都要有命中"的校验。
     *
     * <p>P3.4 评估发现「黄金 ETF 持仓变化」会返回一堆 ETF 笔记：命中的是 ET/TF 和"变化"，
     * 而"黄金"一次都没出现。按 bigram 总数算分时，通用词会盖住"用户到底在找什么"，
     * 所以多词查询要在 token 粒度上做 AND——用户在两个词之间敲空格，意思就是两个都要满足。
     */
    public static List<List<String>> gramGroups(String keyword) {
        List<List<String>> groups = new ArrayList<>();
        for (String token : tokenize(keyword)) {
            if (token.length() < 2) {
                groups.add(List.of(token));
                continue;
            }
            List<String> grams = new ArrayList<>();
            for (int i = 0; i + 2 <= token.length(); i++) {
                grams.add(token.substring(i, i + 2));
            }
            groups.add(grams.isEmpty() ? List.of(token) : grams);
        }
        return groups;
    }

    /**
     * 一个片段至少要命中几个 bigram 才算命中：只有 1 个 bigram 的查询（2 字词）不设门槛，其余要 2 个。
     *
     * <p><b>为什么不按比例上浮</b>：P3.4 试过「命中数 ≥ 查询 bigram 数的 30%」，
     * recall 从 0.838 掉到 0.721，其中「净息差还能不能稳住」直接归零——
     * 因为 bigram 里天然含大量无意义碎片（差还/还能/能不/能稳），分母被撑大，
     * 相关文档的真实命中数永远追不上比例线。这与 P3.0 否掉「半数阈值」是同一个原因，
     * 只是比例调小后更隐蔽。固定 2 能挡掉「只靠一个通用词命中」的噪声，又不会伤长句。
     */
    static int minGramHits(int gramCount) {
        return gramCount <= 1 ? 0 : 2;
    }

    /** 切 token：字母/数字（含中文）连续成段，其余字符（空格、中英标点、括号）都是分隔。 */
    static List<String> tokenize(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < raw.length() && out.size() < MAX_TOKENS; i++) {
            char ch = normalizeWidth(raw.charAt(i));
            if (Character.isLetterOrDigit(ch)) {
                current.append(ch);
            } else if (flush(current, out)) {
                break;
            }
        }
        flush(current, out);
        return List.copyOf(out);
    }

    private static boolean flush(StringBuilder current, Set<String> out) {
        if (current.length() > 0) {
            out.add(current.toString());
            current.setLength(0);
        }
        return out.size() >= MAX_TOKENS;
    }

    /** 全角 ASCII（U+FF01–U+FF5E）与全角空格转半角。 */
    static char normalizeWidth(char ch) {
        if (ch == '\u3000') {
            return ' ';
        }
        if (ch >= '\uFF01' && ch <= '\uFF5E') {
            return (char) (ch - 0xFEE0);
        }
        return ch;
    }

    /**
     * 把检索单元包成 {@code %...%} 的 LIKE 模式，并转义通配符。
     *
     * <p>不转义的话，用户搜 "50%" 或 "a_b" 会变成通配符——结果里出现一堆不相关的东西，
     * 而用户没法知道是"搜得宽"还是"搜错了"。
     */
    public static String likePattern(String term) {
        StringBuilder sb = new StringBuilder(term.length() + 2).append('%');
        for (int i = 0; i < term.length(); i++) {
            char ch = term.charAt(i);
            if (ch == '\\' || ch == '%' || ch == '_') {
                sb.append('\\');
            }
            sb.append(ch);
        }
        return sb.append('%').toString();
    }
}
