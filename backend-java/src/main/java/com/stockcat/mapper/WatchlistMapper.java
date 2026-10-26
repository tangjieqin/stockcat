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
            INSERT INTO watchlist_item (instrument_id, created_at)
            VALUES (#{instrumentId}, now())
            """)
    int insert(@Param("instrumentId") Long instrumentId);

    @Select("SELECT count(*) FROM watchlist_item WHERE instrument_id = #{instrumentId}")
    long existsByInstrumentId(@Param("instrumentId") Long instrumentId);

    @Select("SELECT id FROM watchlist_item WHERE instrument_id = #{instrumentId}")
    Long findIdByInstrumentId(@Param("instrumentId") Long instrumentId);

    @Select("""
            SELECT w.id, w.instrument_id, w.created_at,
                   i.market, i.symbol, i.name, i.instrument_type, i.currency
            FROM watchlist_item w
            JOIN instrument i ON i.id = w.instrument_id
            ORDER BY w.created_at DESC, w.id DESC
            """)
    List<MonitorTargetRow> selectAll();

    @Delete("DELETE FROM watchlist_item WHERE instrument_id = #{instrumentId}")
    int deleteByInstrumentId(@Param("instrumentId") Long instrumentId);
}
