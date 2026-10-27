package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.LibraryChunk;
import com.stockcat.domain.LibraryDocument;
import com.stockcat.domain.LibrarySearchRow;
import com.stockcat.mapper.LibraryChunkMapper;
import com.stockcat.mapper.LibraryDocumentMapper;
import com.stockcat.web.dto.LibraryDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 资料库（P3.1）：导入、切片、列表、详情、删除。
 *
 * <p>三条不变量：
 * <ol>
 *   <li><b>正文不可编辑</b>。要改就重新导入——资料是外部内容，就地改会破坏"引用指向哪一版"的可追溯性。</li>
 *   <li><b>同一篇不许存两份</b>。按规范化正文的 sha256 拦截，冲突时返回已存在的 id 让用户跳过去。</li>
 *   <li><b>文档与片段同生同灭</b>。同一事务写入；删除靠外键 CASCADE，不留孤儿片段。</li>
 * </ol>
 */
@Service
public class LibraryService {

    /** 单篇正文上限：个人资料用不到更长；超长说明是把整本书塞进来了。 */
    static final int MAX_CONTENT_CHARS = 200_000;
    /** 单篇切片上限：防止一篇超大文档把一次导入拖成几分钟。 */
    static final int MAX_CHUNKS = 800;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /** 检索默认/最大返回条数。 */
    static final int DEFAULT_SEARCH_LIMIT = 20;
    static final int MAX_SEARCH_LIMIT = 50;
    /** 同一篇资料最多贡献几个命中片段，见 {@code LibraryChunkMapper#search}。 */
    static final int MAX_CHUNKS_PER_DOCUMENT = 3;
    /** snippet 取命中位置前后各 60 字：够看出上下文，又不至于把整段搬进列表。 */
    static final int SNIPPET_RADIUS = 60;
    /** 返回给前端的命中词上限，用于高亮；按长度降序（长词先匹配，避免"库存"先于"渠道库存"）。 */
    private static final int MAX_MATCHED_TERMS = 12;

    private final LibraryDocumentMapper documentMapper;
    private final LibraryChunkMapper chunkMapper;

    public LibraryService(LibraryDocumentMapper documentMapper, LibraryChunkMapper chunkMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    @Transactional
    public DocumentDetail create(CreateDocumentRequest req) {
        String sourceType = req.sourceType() == null || req.sourceType().isBlank()
                ? "PASTE" : req.sourceType().trim().toUpperCase();
        if (!Set.of("PASTE", "FILE").contains(sourceType)) {
            throw ApiException.validationError(
                    "sourceType 只支持 PASTE 或 FILE（URL 抓取未实现，请复制正文后粘贴导入）");
        }

        String content = normalize(req.content());
        if (content.isEmpty()) {
            throw ApiException.validationError("正文为空，没有可导入的内容");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw ApiException.validationError(
                    "正文超过 " + MAX_CONTENT_CHARS + " 字，请拆分后再导入（单篇资料不做长篇压缩）");
        }

        String hash = sha256(content);
        Long existing = documentMapper.findIdByHash(hash);
        if (existing != null) {
            // 409 而不是 200：让前端能提示"这篇已经在库里"并给出跳转，而不是假装新建成功
            throw ApiException.conflict("这篇资料已在库中（id=" + existing + "），未重复导入");
        }

        List<LibraryChunkSplitter.Chunk> slices = LibraryChunkSplitter.split(content);
        if (slices.isEmpty()) {
            throw ApiException.validationError("正文没有可切分的段落");
        }
        if (slices.size() > MAX_CHUNKS) {
            throw ApiException.validationError(
                    "切分出 " + slices.size() + " 个片段，超过单篇上限 " + MAX_CHUNKS + "，请拆分后再导入");
        }

        LibraryDocument doc = new LibraryDocument();
        doc.setTitle(req.title().trim());
        doc.setSourceType(sourceType);
        doc.setSourceRef(blankToNull(req.sourceRef()));
        doc.setPublisher(blankToNull(req.publisher()));
        doc.setPublishedAt(req.publishedAt());
        doc.setContent(content);
        doc.setContentHash(hash);
        doc.setCharCount(content.length());
        doc.setStatus("ACTIVE");
        doc.setNote(blankToNull(req.note()));
        documentMapper.insert(doc);

        List<LibraryChunk> rows = new ArrayList<>(slices.size());
        for (LibraryChunkSplitter.Chunk slice : slices) {
            LibraryChunk row = new LibraryChunk();
            row.setDocumentId(doc.getId());
            row.setSeq(slice.seq());
            row.setHeading(slice.heading());
            row.setContent(slice.content());
            row.setCharStart(slice.charStart());
            row.setCharEnd(slice.charEnd());
            rows.add(row);
        }
        chunkMapper.batchInsert(rows);

        if (req.instrumentIds() != null && !req.instrumentIds().isEmpty()) {
            documentMapper.insertInstruments(doc.getId(), req.instrumentIds());
        }
        return detail(doc.getId());
    }

    public PageResult list(String keyword, Integer page, Integer size) {
        int effectiveSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int effectivePage = page == null || page <= 0 ? 1 : page;
        String kw = blankToNull(keyword);

        long total = documentMapper.countByKeyword(kw);
        List<DocumentSummary> items = documentMapper
                .selectPage(kw, effectiveSize, (effectivePage - 1) * effectiveSize)
                .stream().map(LibraryService::toSummary).toList();
        return new PageResult(total, effectivePage, effectiveSize, items);
    }

    public DocumentDetail detail(Long id) {
        LibraryDocument doc = documentMapper.findById(id);
        if (doc == null) {
            throw ApiException.notFound("资料不存在: id=" + id);
        }
        List<ChunkView> chunks = chunkMapper.selectByDocumentId(id).stream()
                .map(c -> new ChunkView(c.getId(), c.getSeq(), c.getHeading(), c.getContent(),
                        c.getCharStart(), c.getCharEnd()))
                .toList();
        return new DocumentDetail(doc.getId(), doc.getTitle(), doc.getSourceType(),
                doc.getSourceRef(), doc.getPublisher(), doc.getPublishedAt(), doc.getImportedAt(),
                doc.getCharCount(), chunks.size(), doc.getStatus(), doc.getNote(), doc.getContent(),
                documentMapper.selectInstrumentIds(id), chunks);
    }

    /**
     * 删除资料。片段由外键 CASCADE 清理。
     *
     * <p>要求 {@code confirm=true}：资料删了通常没有第二份，而"误点删除"是真实存在的手滑。
     * 未确认时把将删除的片段数告诉你，而不是只抛一个 400。
     */
    @Transactional
    public int delete(Long id, boolean confirm) {
        LibraryDocument doc = documentMapper.findById(id);
        if (doc == null) {
            throw ApiException.notFound("资料不存在: id=" + id);
        }
        int chunkCount = chunkMapper.countByDocumentId(id);
        if (!confirm) {
            throw ApiException.validationError(
                    "删除资料会同时删掉 " + chunkCount + " 个片段，必须携带 confirm=true");
        }
        documentMapper.deleteById(id);
        return chunkCount;
    }

    // ---- 检索（P3.2） ----

    /**
     * 片段级检索：拆 bigram → ILIKE 组合 → 按分数与时间排序。
     *
     * <p>为什么以片段为单位而不是整篇：用户搜"渠道库存"想知道的是"哪一段在讲这个"，
     * 整篇命中只能告诉他"这篇里某个地方提过"，还得自己翻。
     */
    public SearchResult search(String keyword, Integer limit, Long instrumentId) {
        LibrarySearchTerms.Terms terms = LibrarySearchTerms.of(keyword);
        if (terms.isEmpty()) {
            throw ApiException.validationError("请输入检索词");
        }
        int effectiveLimit = limit == null || limit <= 0
                ? DEFAULT_SEARCH_LIMIT : Math.min(limit, MAX_SEARCH_LIMIT);

        // 2 字查询只切出 1 个 bigram，要求它命中 ≥2 次是不可能的——那种情况门槛是 0，即不设限
        int minGramHits = LibrarySearchTerms.minGramHits(terms.grams().size());
        List<LibrarySearchRow> rows = chunkMapper.search(
                terms.grams().stream().map(LibrarySearchTerms::likePattern).toList(),
                terms.grams(),
                terms.wholes().stream().map(LibrarySearchTerms::likePattern).toList(),
                LibrarySearchTerms.WHOLE_TERM_WEIGHT,
                minGramHits,
                instrumentId,
                MAX_CHUNKS_PER_DOCUMENT,
                effectiveLimit);
        rows = requireEveryTokenHit(rows, keyword);
        List<SearchHit> hits = rows.stream()
                .map(row -> {
                    List<String> matched = matchedTerms(row.getContent(), terms);
                    return new SearchHit(
                            row.getChunkId(), row.getDocumentId(), row.getDocumentTitle(),
                            row.getSeq(), row.getHeading(),
                            snippet(row.getContent(), matched),
                            row.getCharStart(), row.getCharEnd(),
                            row.getSourceType(), row.getSourceRef(), row.getPublisher(),
                            row.getPublishedAt(), row.getImportedAt(),
                            row.getScore() == null ? 0 : row.getScore(),
                            matched,
                            // 正文里一个词都没命中，只可能是标题命中的
                            matched.isEmpty() && !matchedTerms(row.getDocumentTitle(), terms).isEmpty());
                })
                .toList();
        return new SearchResult(keyword.trim(), terms.wholes(), thinTitleOnlyHits(hits));
    }

    /**
     * 多词查询：每个词都要有 bigram 命中（token 粒度的 AND）。
     *
     * <p>SQL 里的分数是"命中多少个 bigram"，通用词会把分数堆起来：搜「黄金 ETF 持仓变化」时，
     * 一篇讲宽基 ETF 的笔记靠 ET/TF 和"变化"就能进结果，而"黄金"一次都没出现。
     * 用户在两个词之间敲空格，意思就是两个词都要满足。
     *
     * <p><b>只在查询被切成多个 token 时生效</b>。「茅台批价」这类"实体+属性"的写法在中文里
     * 没有空格，是一个 token，不受影响——否则原文里"茅台"与"批价"分处两段时会被判成无关，
     * 这是 P3.4 第一轮改动踩过的坑（「600519 三季报」因此掉到 0 召回，根因其实是语料
     * 那篇三季报正文没写代码，语料修正后才把这条规则放回来）。
     *
     * <p>放在 SQL 之后而不是 SQL 里，是因为按 token 分组传参要拼二维数组，
     * 可读性和可测性都更差；代价是过滤发生在 LIMIT 之后，返回条数可能少于 limit——
     * 这正好符合"宁缺毋滥"。
     */
    static List<LibrarySearchRow> requireEveryTokenHit(List<LibrarySearchRow> rows, String keyword) {
        List<List<String>> groups = LibrarySearchTerms.gramGroups(keyword);
        if (groups.size() <= 1 || rows.isEmpty()) {
            return rows;
        }
        List<LibrarySearchRow> kept = new ArrayList<>(rows.size());
        for (LibrarySearchRow row : rows) {
            String text = ((row.getDocumentTitle() == null ? "" : row.getDocumentTitle())
                    + " " + (row.getContent() == null ? "" : row.getContent())).toLowerCase(Locale.ROOT);
            boolean allTokensHit = true;
            for (List<String> group : groups) {
                boolean anyGramHit = false;
                for (String gram : group) {
                    if (text.contains(gram.toLowerCase(Locale.ROOT))) {
                        anyGramHit = true;
                        break;
                    }
                }
                if (!anyGramHit) {
                    allTokensHit = false;
                    break;
                }
            }
            if (allTokensHit) {
                kept.add(row);
            }
        }
        return kept;
    }

    /**
     * 收掉重复的"仅标题命中"：同一篇里标题命中会让每个片段都得分，
     * 搜"调研纪要"于是返回同一篇的三段，其中两段正文里根本没有这个词。
     *
     * <p>规则：某篇只要有正文真命中，就丢掉它的标题命中片段；整篇都只是标题命中的，留一条（最短 seq）。
     */
    static List<SearchHit> thinTitleOnlyHits(List<SearchHit> hits) {
        List<Long> docsWithContentHit = hits.stream()
                .filter(h -> !h.matchedTerms().isEmpty())
                .map(SearchHit::documentId)
                .distinct()
                .toList();
        List<SearchHit> out = new ArrayList<>(hits.size());
        List<Long> titleOnlyKept = new ArrayList<>();
        for (SearchHit hit : hits) {
            if (!hit.matchedTerms().isEmpty()) {
                out.add(hit);
            } else if (!docsWithContentHit.contains(hit.documentId())
                    && !titleOnlyKept.contains(hit.documentId())) {
                titleOnlyKept.add(hit.documentId());
                out.add(hit);
            }
        }
        return out;
    }

    // ---- 检索结果的片段截取 ----

    /**
     * 命中的词：只保留真正出现在正文里的，并丢掉被整词覆盖的碎片。
     *
     * <p>不丢的话，"600519" 会同时返回 60/00/05/51/19，前端高亮把这些数字标成一串色块；
     * "渠道库存" 会连 "道库" 这种跨词碎片一起标出来。整词命中时，只标整词就够了。
     */
    static List<String> matchedTerms(String content, LibrarySearchTerms.Terms terms) {
        List<String> wholes = new ArrayList<>();
        for (String term : terms.wholes()) {
            if (indexOfIgnoreCase(content, term, 0) >= 0) {
                wholes.add(term);
            }
        }
        List<String> matched = new ArrayList<>(wholes);
        for (String gram : terms.grams()) {
            if (matched.contains(gram) || wholes.stream().anyMatch(w -> w.contains(gram))) {
                continue;
            }
            if (indexOfIgnoreCase(content, gram, 0) >= 0) {
                matched.add(gram);
            }
        }
        return matched.stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .limit(MAX_MATCHED_TERMS)
                .toList();
    }

    /**
     * 取命中位置前后各 {@link #SNIPPET_RADIUS} 字，两端加省略号。
     *
     * <p>没有命中时返回开头——理论上不会发生（SQL 已保证至少一个 bigram 命中），
     * 但 ILIKE 与 {@code indexOf} 在极端字符上可能不一致，宁可给出开头也不要抛异常。
     */
    static String snippet(String content, List<String> needles) {
        if (content == null || content.isBlank()) {
            return "";
        }
        int hit = -1;
        int hitLength = 0;
        for (String needle : needles) {
            if (needle.isEmpty()) {
                continue;
            }
            int found = indexOfIgnoreCase(content, needle, 0);
            if (found >= 0 && (hit < 0 || found < hit)) {
                hit = found;
                hitLength = needle.length();
            }
        }
        if (hit < 0) {
            hit = 0;
            hitLength = 0;
        }

        int start = Math.max(0, hit - SNIPPET_RADIUS);
        int end = Math.min(content.length(), hit + hitLength + SNIPPET_RADIUS);
        // 别把 emoji 之类的代理对切成半个字符
        if (start > 0 && Character.isLowSurrogate(content.charAt(start))
                && Character.isHighSurrogate(content.charAt(start - 1))) {
            start--;
        }
        if (end < content.length() && Character.isLowSurrogate(content.charAt(end))
                && Character.isHighSurrogate(content.charAt(end - 1))) {
            end++;
        }

        String body = content.substring(start, end).replace('\n', ' ').replaceAll(" {2,}", " ").trim();
        return (start > 0 ? "…" : "") + body + (end < content.length() ? "…" : "");
    }

    /** 大小写不敏感查找：ILIKE 能匹配上的，snippet 也得能找到，否则高亮会凭空缺失。 */
    static int indexOfIgnoreCase(String haystack, String needle, int from) {
        if (needle.isEmpty() || haystack.length() < needle.length()) {
            return -1;
        }
        int last = haystack.length() - needle.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    // ---- 规范化与哈希 ----

    /**
     * 规范化正文：统一换行、去行尾空白、压缩连续空行、去首尾空白、去 BOM。
     *
     * <p>这既是"同一篇资料不同排版应视为重复"的前提，也让 {@code charStart}/{@code charEnd}
     * 指向的是**规范化后的正文**——入库的是这一版，引用也基于这一版，两边一致。
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        StringBuilder sb = new StringBuilder(text.length());
        int blankRun = 0;
        for (String line : text.split("\n", -1)) {
            String trimmed = stripTrailing(line);
            if (trimmed.isEmpty()) {
                blankRun++;
                // 最多保留一个空行：连续空行是排版噪声，不是内容
                if (blankRun > 1) {
                    continue;
                }
            } else {
                blankRun = 0;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(trimmed);
        }
        return sb.toString().trim();
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
            end--;
        }
        return line.substring(0, end);
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static DocumentSummary toSummary(LibraryDocument d) {
        return new DocumentSummary(d.getId(), d.getTitle(), d.getSourceType(), d.getSourceRef(),
                d.getPublisher(), d.getPublishedAt(), d.getImportedAt(), d.getCharCount(),
                d.getChunkCount(), d.getStatus());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
