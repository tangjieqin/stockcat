package com.stockcat.mapper;

import com.stockcat.domain.LibraryChunk;
import com.stockcat.domain.LibrarySearchRow;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** 资料片段（V9 `library_chunk`，P3.1）。删除文档时由外键 CASCADE 清理，这里不用管。 */
@Mapper
public interface LibraryChunkMapper {

    /** 一次导入的所有片段一起插入：分批插会让"文档有了但片段没写完"变成可能的中间态。 */
    @Insert("""
            <script>
            INSERT INTO library_chunk (document_id, seq, heading, content, char_start, char_end)
            VALUES
            <foreach collection="chunks" item="c" separator=",">
                (#{c.documentId}, #{c.seq}, #{c.heading}, #{c.content}, #{c.charStart}, #{c.charEnd})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("chunks") List<LibraryChunk> chunks);

    @Select("""
            SELECT id, document_id, seq, heading, content, char_start, char_end, created_at
            FROM library_chunk WHERE document_id = #{documentId} ORDER BY seq ASC
            """)
    List<LibraryChunk> selectByDocumentId(@Param("documentId") Long documentId);

    @Select("""
            SELECT id, document_id, seq, heading, content, char_start, char_end, created_at
            FROM library_chunk WHERE id = #{id}
            """)
    LibraryChunk findById(@Param("id") Long id);

    @Select("SELECT count(*) FROM library_chunk WHERE document_id = #{documentId}")
    int countByDocumentId(@Param("documentId") Long documentId);

    /**
     * 片段级检索（P3.2）：数组里每个元素是已转义并包好 {@code %} 的 LIKE 模式。
     *
     * <p>分数 = 整词命中数 × 权重 + bigram 命中数，与 P3.0 评估脚本口径一致。
     * 检索列是 {@code 标题 + 正文}：用户常说"我记得有一篇讲渠道库存的"，但正文里未必写着标题里的词。
     * 标题命中会让该篇每个片段都得分，靠下面的 {@code rn} 限制住，不会一篇灌满整页。
     *
     * <p>用 {@code CROSS JOIN LATERAL} 是因为分数要在 SELECT、窗口函数、ORDER BY 三处出现，
     * 各写一遍既啰嗦又容易出现"排的是 A、显示的是 B"。
     *
     * <p>{@code rn <= perDocument} 限制单篇最多贡献几个片段：一篇长资料里十段都在谈同一件事时，
     * 让它占满整个结果页对用户没有帮助——他需要的是"这篇讲了，去第 3 段看"，不是十份同样的证据。
     *
     * <p><b>{@code minGramHits} 是 P3.4 评估倒逼出来的</b>：语料扩到 50 篇后，
     * 「猪周期能繁母猪存栏」「黄金 ETF 持仓变化」这类长查询会拆出「周期」「变化」这种通用 bigram，
     * 于是三条应当无结果的查询各返回 5 条噪声——用户搜养猪，看到一堆讲白酒周期的段落。
     * 规则是"命中 ≥ 2 个 bigram 才算命中"，比 P3.0 试过的"半数阈值"宽松得多
     * （长句 8 个 bigram 只要求 2 个，不会像阈值那样把长句打成空结果）。
     * 只有 1 个 bigram 的查询（2 字词）传 0，即不限制：2 字查询本来就是精确匹配，不存在误报。
     *
     * <p><b>{@code occurrences} 是同分时的第二排序键</b>（P3.4 之后补）：原先同分按发布时间排，
     * 而"发布得新"和"更相关"没有关系——评估里 4 条漏召全是"正确答案排在第 6–8 位"，
     * 不是没找到。现在同分时按命中词在该片段里出现的次数排：认真讲渠道库存的段落会提 3 次，
     * 顺带蹭到一次的排后面。
     *
     * <p>它刻意<b>不进 score</b>。score 的口径要和 P3.0 评估脚本对得上（整词命中数 × 权重 +
     * bigram 命中去重数），一旦把次数加进去，两次评估的数字就不再可比；而且长片段天然出现更多次，
     * 直接加权会让"长"冒充"相关"。做成 tiebreaker，只影响同分内部顺序，风险最小。
     *
     * <p>计数用 {@code position(...)} 判存在、{@code replace} 算次数，而不是 {@code LIKE}：
     * 裸词里万一出现 {@code %} 或 {@code _} 会被当成通配符，{@code position} 没有这个问题。
     * 这里传的是不带通配符的裸词（{@code rawGrams}），与上面那些 LIKE 模式是两个数组。
     */
    @Select("""
            <script>
            SELECT t.chunk_id, t.document_id, t.seq, t.heading, t.content,
                   t.char_start, t.char_end,
                   t.document_title, t.source_type, t.source_ref, t.publisher,
                   t.published_at, t.imported_at, t.score
            FROM (
                SELECT c.id AS chunk_id, c.document_id, c.seq, c.heading, c.content,
                       c.char_start, c.char_end,
                       d.title AS document_title, d.source_type, d.source_ref, d.publisher,
                       d.published_at, d.imported_at,
                       s.score, s.occurrences,
                       row_number() OVER (PARTITION BY c.document_id
                                          ORDER BY s.score DESC, s.occurrences DESC, c.seq ASC) AS rn
                FROM library_chunk c
                JOIN library_document d ON d.id = c.document_id
                CROSS JOIN LATERAL (
                    SELECT m.gram_hits + #{wholeWeight} * m.whole_hits AS score,
                           m.gram_hits, m.occurrences
                    FROM (
                        SELECT
                            (SELECT count(*) FROM unnest(ARRAY[
                                <foreach collection="grams" item="g" separator=",">#{g}</foreach>
                            ]::text[]) g
                                WHERE txt ILIKE g ESCAPE '\\') AS gram_hits,
                            <choose>
                                <when test="wholes != null and wholes.size() > 0">
                                    (SELECT count(*) FROM unnest(ARRAY[
                                        <foreach collection="wholes" item="w" separator=",">#{w}</foreach>
                                    ]::text[]) w
                                        WHERE txt ILIKE w ESCAPE '\\')
                                </when>
                                <otherwise>0</otherwise>
                            </choose> AS whole_hits,
                            (SELECT COALESCE(sum(
                                        (length(txt) - length(replace(txt, lower(g), '')))
                                            / length(lower(g))
                                    ), 0)
                             FROM unnest(ARRAY[
                                 <foreach collection="rawGrams" item="r" separator=",">#{r}</foreach>
                             ]::text[]) g
                             WHERE position(lower(g) in txt) > 0) AS occurrences
                        FROM (SELECT lower(d.title || ' ' || c.content) AS txt) x
                    ) m
                ) s
                WHERE d.status = 'ACTIVE' AND s.score > 0 AND s.gram_hits &gt;= #{minGramHits}
                <if test="instrumentId != null">
                    AND EXISTS (SELECT 1 FROM library_document_instrument li
                                WHERE li.document_id = d.id AND li.instrument_id = #{instrumentId})
                </if>
            ) t
            WHERE t.rn &lt;= #{perDocument}
            ORDER BY t.score DESC, t.occurrences DESC,
                     t.published_at DESC NULLS LAST, t.imported_at DESC, t.chunk_id
            LIMIT #{limit}
            </script>
            """)
    List<LibrarySearchRow> search(@Param("grams") List<String> grams,
                                  @Param("rawGrams") List<String> rawGrams,
                                  @Param("wholes") List<String> wholes,
                                  @Param("wholeWeight") int wholeWeight,
                                  @Param("minGramHits") int minGramHits,
                                  @Param("instrumentId") Long instrumentId,
                                  @Param("perDocument") int perDocument,
                                  @Param("limit") int limit);
}
