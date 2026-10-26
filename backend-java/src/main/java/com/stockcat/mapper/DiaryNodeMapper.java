package com.stockcat.mapper;

import com.stockcat.domain.DiaryNode;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DiaryNodeMapper {

    @Insert("""
            INSERT INTO diary_node (parent_id, node_type, title, content, related_instrument_ids, sort_order, created_at, updated_at)
            VALUES (#{parentId}, #{nodeType}, #{title}, #{content}, CAST(#{relatedInstrumentIds} AS jsonb), #{sortOrder}, now(), now())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DiaryNode node);

    @Select("""
            SELECT id, parent_id, node_type, title, content,
                   related_instrument_ids::text AS related_instrument_ids,
                   sort_order, created_at, updated_at
            FROM diary_node WHERE id = #{id}
            """)
    DiaryNode findById(@Param("id") Long id);

    @Select("""
            SELECT id, parent_id, node_type, title, content,
                   related_instrument_ids::text AS related_instrument_ids,
                   sort_order, created_at, updated_at
            FROM diary_node
            ORDER BY sort_order ASC, id ASC
            """)
    List<DiaryNode> selectAll();

    @Update("""
            <script>
            UPDATE diary_node
            <set>
                updated_at = now()
                <if test="title != null">, title = #{title}</if>
                <if test="content != null">, content = #{content}</if>
                <if test="relatedInstrumentIds != null">, related_instrument_ids = CAST(#{relatedInstrumentIds} AS jsonb)</if>
                <if test="sortOrder != null">, sort_order = #{sortOrder}</if>
                <if test="parentId != null">, parent_id = #{parentId}</if>
                <if test="parentIdIsNull">, parent_id = NULL</if>
            </set>
            WHERE id = #{id}
            </script>
            """)
    int updateFields(@Param("id") Long id, @Param("title") String title, @Param("content") String content,
                     @Param("relatedInstrumentIds") String relatedInstrumentIds,
                     @Param("sortOrder") Integer sortOrder,
                     @Param("parentId") Long parentId, @Param("parentIdIsNull") boolean parentIdIsNull);

    /**
     * 递归删除节点及其整个子树（用于 FOLDER 级联删除）。
     */
    @Delete("""
            DELETE FROM diary_node WHERE id IN (
                WITH RECURSIVE subtree AS (
                    SELECT id FROM diary_node WHERE id = #{id}
                    UNION ALL
                    SELECT d.id FROM diary_node d JOIN subtree s ON d.parent_id = s.id
                )
                SELECT id FROM subtree
            )
            """)
    int deleteSubtree(@Param("id") Long id);

    @Select("""
            SELECT count(*) FROM diary_node
            WHERE node_type = 'DOC' AND id IN (
                WITH RECURSIVE subtree AS (
                    SELECT id FROM diary_node WHERE id = #{id}
                    UNION ALL
                    SELECT d.id FROM diary_node d JOIN subtree s ON d.parent_id = s.id
                )
                SELECT id FROM subtree
            )
            """)
    long countDocsInSubtree(@Param("id") Long id);

    @Select("""
            <script>
            SELECT id, parent_id, node_type, title, content,
                   related_instrument_ids::text AS related_instrument_ids,
                   sort_order, created_at, updated_at
            FROM diary_node
            <where>
                node_type = 'DOC'
                <if test="keyword != null">AND (title ILIKE '%' || #{keyword} || '%' OR content ILIKE '%' || #{keyword} || '%')</if>
            </where>
            ORDER BY updated_at DESC
            LIMIT #{limit}
            </script>
            """)
    List<DiaryNode> searchDocs(@Param("keyword") String keyword, @Param("limit") int limit);
}
