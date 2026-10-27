package com.stockcat.mapper;

import com.stockcat.domain.MonitorTargetRow;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 自选列表（P0 单用户，无 user 维度）。行结构与监控标的类似：watchlist JOIN instrument。
 */
@Mapper
public interface WatchlistMapper {

    @Insert("""
            <script>
            INSERT INTO watchlist_item (instrument_id, group_name, created_at)
            VALUES (#{instrumentId}, #{groupName}, now())
            </script>
            """)
    int insert(@Param("instrumentId") Long instrumentId, @Param("groupName") String groupName);

    @Select("SELECT count(*) FROM watchlist_item WHERE instrument_id = #{instrumentId}")
    long existsByInstrumentId(@Param("instrumentId") Long instrumentId);

    @Select("SELECT id FROM watchlist_item WHERE instrument_id = #{instrumentId}")
    Long findIdByInstrumentId(@Param("instrumentId") Long instrumentId);

    @Select("""
            SELECT w.id, w.instrument_id, w.group_name, w.created_at,
                   i.market, i.symbol, i.name, i.instrument_type, i.currency
            FROM watchlist_item w
            JOIN instrument i ON i.id = w.instrument_id
            ORDER BY w.created_at DESC, w.id DESC
            """)
    List<MonitorTargetRow> selectAll();

    /** 分组列表（DISTINCT，NULL=默认自选不返回）；空分组随最后一个成员移出自然消失。 */
    @Select("""
            SELECT DISTINCT group_name FROM watchlist_item
            WHERE group_name IS NOT NULL
            ORDER BY group_name
            """)
    List<String> selectDistinctGroups();

    @Update("""
            <script>
            UPDATE watchlist_item
            <set>
              <choose>
                <when test="groupName != null">group_name = #{groupName},</when>
                <otherwise>group_name = NULL,</otherwise>
              </choose>
            </set>
            WHERE instrument_id = #{instrumentId}
            </script>
            """)
    int updateGroupName(@Param("instrumentId") Long instrumentId, @Param("groupName") String groupName);

    @Delete("DELETE FROM watchlist_item WHERE instrument_id = #{instrumentId}")
    int deleteByInstrumentId(@Param("instrumentId") Long instrumentId);
}
