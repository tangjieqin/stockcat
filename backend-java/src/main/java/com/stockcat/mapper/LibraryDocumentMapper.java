package com.stockcat.mapper;

import com.stockcat.domain.LibraryDocument;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** 资料文档（V9 `library_document`，P3.1）。 */
@Mapper
public interface LibraryDocumentMapper {

    @Insert("""
            INSERT INTO library_document
                (title, source_type, source_ref, publisher, published_at,
                 content, content_hash, char_count, status, note)
            VALUES
                (#{title}, #{sourceType}, #{sourceRef}, #{publisher}, #{publishedAt},
                 #{content}, #{contentHash}, #{charCount}, #{status}, #{note})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LibraryDocument doc);

    @Select("""
            SELECT id, title, source_type, source_ref, publisher, published_at,
                   imported_at, content, content_hash, char_count, status, note
            FROM library_document WHERE id = #{id}
            """)
    LibraryDocument findById(@Param("id") Long id);

    /** 去重用：只取 id，不把整篇正文读出来。 */
    @Select("SELECT id FROM library_document WHERE content_hash = #{hash}")
    Long findIdByHash(@Param("hash") String hash);

    /**
     * 列表：**不返回 content**——列表页不显示全文，把大字段带出来只是浪费带宽与内存。
     * chunkCount 用子查询算，避免前端逐篇再请求一次。
     */
    @Select("""
            <script>
            SELECT d.id, d.title, d.source_type, d.source_ref, d.publisher, d.published_at,
                   d.imported_at, d.char_count, d.status, d.note,
                   (SELECT count(*) FROM library_chunk c WHERE c.document_id = d.id) AS chunk_count
            FROM library_document d
            WHERE d.status = 'ACTIVE'
            <if test="keyword != null and keyword != ''">
              AND (d.title ILIKE '%' || #{keyword} || '%' OR d.content ILIKE '%' || #{keyword} || '%')
            </if>
            ORDER BY d.imported_at DESC, d.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<LibraryDocument> selectPage(@Param("keyword") String keyword,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    @Select("""
            <script>
            SELECT count(*) FROM library_document d
            WHERE d.status = 'ACTIVE'
            <if test="keyword != null and keyword != ''">
              AND (d.title ILIKE '%' || #{keyword} || '%' OR d.content ILIKE '%' || #{keyword} || '%')
            </if>
            </script>
            """)
    long countByKeyword(@Param("keyword") String keyword);

    @Delete("DELETE FROM library_document WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    // ---- 关联标的（手动维护） ----

    @Insert("""
            <script>
            INSERT INTO library_document_instrument (document_id, instrument_id) VALUES
            <foreach collection="instrumentIds" item="iid" separator=",">
                (#{documentId}, #{iid})
            </foreach>
            ON CONFLICT DO NOTHING
            </script>
            """)
    int insertInstruments(@Param("documentId") Long documentId,
                          @Param("instrumentIds") List<Long> instrumentIds);

    @Select("""
            SELECT instrument_id FROM library_document_instrument
            WHERE document_id = #{documentId} ORDER BY instrument_id
            """)
    List<Long> selectInstrumentIds(@Param("documentId") Long documentId);
}
