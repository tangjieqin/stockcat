package com.stockcat.mapper;

import com.stockcat.domain.MonitorTargetRow;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MonitorTargetMapper {

    @Insert("""
            INSERT INTO monitor_target (instrument_id, enabled, created_at)
            VALUES (#{instrumentId}, TRUE, now())
            """)
    int insert(@Param("instrumentId") Long instrumentId);

    @Select("SELECT id FROM monitor_target WHERE instrument_id = #{instrumentId}")
    Long findIdByInstrumentId(@Param("instrumentId") Long instrumentId);

    @Select("SELECT count(*) FROM monitor_target WHERE instrument_id = #{instrumentId}")
    long existsByInstrumentId(@Param("instrumentId") Long instrumentId);

    @Select("SELECT count(*) FROM monitor_target")
    long countAll();

    @Select("SELECT count(*) FROM monitor_target WHERE enabled = TRUE")
    long countEnabled();

    @Select("""
            SELECT t.id, t.instrument_id, t.enabled, t.created_at,
                   i.market, i.symbol, i.name, i.instrument_type, i.currency
            FROM monitor_target t
            JOIN instrument i ON i.id = t.instrument_id
            ORDER BY t.created_at DESC, t.id DESC
            """)
    List<MonitorTargetRow> selectAll();

    @Select("""
            SELECT t.id, t.instrument_id, t.enabled, t.created_at,
                   i.market, i.symbol, i.name, i.instrument_type, i.currency
            FROM monitor_target t
            JOIN instrument i ON i.id = t.instrument_id
            WHERE t.id = #{id}
            """)
    MonitorTargetRow findById(@Param("id") Long id);

    @Update("UPDATE monitor_target SET enabled = #{enabled} WHERE id = #{id}")
    int updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);

    @Delete("DELETE FROM monitor_target WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
