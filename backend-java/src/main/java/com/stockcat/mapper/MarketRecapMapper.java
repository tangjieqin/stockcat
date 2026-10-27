package com.stockcat.mapper;

import com.stockcat.domain.MarketRecap;
import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface MarketRecapMapper {

    @Insert("""
            INSERT INTO market_recap (recap_date, title, market_summary, sector_notes, content,
                                      related_alert_event_ids, status,
                                      ai_generated_at, ai_model, ai_data_as_of, ai_limitations,
                                      created_at, updated_at)
            VALUES (#{recapDate}, #{title}, #{marketSummary}, #{sectorNotes}, #{content},
                    CAST(#{relatedAlertEventIds} AS jsonb), #{status},
                    #{aiGeneratedAt}, #{aiModel}, #{aiDataAsOf}, CAST(#{aiLimitations} AS jsonb),
                    now(), now())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MarketRecap recap);

    @Select("""
            SELECT id, recap_date, title, market_summary, sector_notes, content,
                   related_alert_event_ids::text AS related_alert_event_ids,
                   status, ai_generated_at, ai_model, ai_data_as_of, ai_limitations::text AS ai_limitations,
                   created_at, updated_at
            FROM market_recap WHERE id = #{id}
            """)
    MarketRecap findById(@Param("id") Long id);

    /** 按日期取当天那一篇（recap_date 唯一）。用于「AI 不得覆盖 MANUAL」的判断。 */
    @Select("""
            SELECT id, recap_date, title, market_summary, sector_notes, content,
                   related_alert_event_ids::text AS related_alert_event_ids,
                   status, ai_generated_at, ai_model, ai_data_as_of, ai_limitations::text AS ai_limitations,
                   created_at, updated_at
            FROM market_recap WHERE recap_date = #{date}
            """)
    MarketRecap findByDate(@Param("date") LocalDate date);

    @Select("SELECT count(*) FROM market_recap WHERE recap_date = #{date}")
    long existsByDate(@Param("date") LocalDate date);

    @Select("""
            <script>
            SELECT id, recap_date, title, market_summary, sector_notes, content,
                   related_alert_event_ids::text AS related_alert_event_ids,
                   status, ai_generated_at, ai_model, ai_data_as_of, ai_limitations::text AS ai_limitations,
                   created_at, updated_at
            FROM market_recap
            <where>
                <if test="date != null">AND recap_date = #{date}</if>
                <if test="from != null">AND recap_date &gt;= #{from}</if>
                <if test="to != null">AND recap_date &lt;= #{to}</if>
                <if test="keyword != null">AND (title ILIKE '%' || #{keyword} || '%'
                     OR content ILIKE '%' || #{keyword} || '%'
                     OR market_summary ILIKE '%' || #{keyword} || '%'
                     OR sector_notes ILIKE '%' || #{keyword} || '%')</if>
            </where>
            ORDER BY recap_date DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<MarketRecap> selectPage(@Param("date") LocalDate date,
                                 @Param("from") LocalDate from, @Param("to") LocalDate to,
                                 @Param("keyword") String keyword,
                                 @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT count(*) FROM market_recap
            <where>
                <if test="date != null">AND recap_date = #{date}</if>
                <if test="from != null">AND recap_date &gt;= #{from}</if>
                <if test="to != null">AND recap_date &lt;= #{to}</if>
                <if test="keyword != null">AND (title ILIKE '%' || #{keyword} || '%'
                     OR content ILIKE '%' || #{keyword} || '%'
                     OR market_summary ILIKE '%' || #{keyword} || '%'
                     OR sector_notes ILIKE '%' || #{keyword} || '%')</if>
            </where>
            </script>
            """)
    long countPage(@Param("date") LocalDate date,
                   @Param("from") LocalDate from, @Param("to") LocalDate to,
                   @Param("keyword") String keyword);

    @Update("""
            <script>
            UPDATE market_recap
            <set>
                updated_at = now()
                <if test="title != null">, title = #{title}</if>
                <if test="marketSummary != null">, market_summary = #{marketSummary}</if>
                <if test="sectorNotes != null">, sector_notes = #{sectorNotes}</if>
                <if test="content != null">, content = #{content}</if>
                <if test="relatedAlertEventIds != null">, related_alert_event_ids = CAST(#{relatedAlertEventIds} AS jsonb)</if>
                <if test="status != null">, status = #{status}</if>
            </set>
            WHERE id = #{id}
            </script>
            """)
    int updateFields(@Param("id") Long id, @Param("title") String title,
                     @Param("marketSummary") String marketSummary, @Param("sectorNotes") String sectorNotes,
                     @Param("content") String content, @Param("relatedAlertEventIds") String relatedAlertEventIds,
                     @Param("status") String status);

    /**
     * 覆盖已有 AI 草稿：正文与溯源信息一起刷新，状态恒为 AI_DRAFT。
     *
     * <p>不复用 {@link #updateFields}：那条路径是"用户编辑"，会把状态转成 MANUAL；
     * 重新生成草稿必须留着草稿状态，两者的语义不能混。
     */
    @Update("""
            UPDATE market_recap
            SET updated_at = now(),
                title = #{title},
                market_summary = #{marketSummary},
                sector_notes = #{sectorNotes},
                content = #{content},
                ai_generated_at = #{aiGeneratedAt},
                ai_model = #{aiModel},
                ai_data_as_of = #{aiDataAsOf},
                ai_limitations = CAST(#{aiLimitations} AS jsonb)
            WHERE id = #{id} AND status = 'AI_DRAFT'
            """)
    int updateDraft(@Param("id") Long id, @Param("title") String title,
                    @Param("marketSummary") String marketSummary, @Param("sectorNotes") String sectorNotes,
                    @Param("content") String content, @Param("aiGeneratedAt") OffsetDateTime aiGeneratedAt,
                    @Param("aiModel") String aiModel, @Param("aiDataAsOf") OffsetDateTime aiDataAsOf,
                    @Param("aiLimitations") String aiLimitations);

    @Delete("DELETE FROM market_recap WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
