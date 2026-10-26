package com.stockcat.mapper;

import com.stockcat.domain.PositionSnapshot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface PositionSnapshotMapper {

    @Delete("DELETE FROM position_snapshot WHERE trade_date BETWEEN #{from} AND #{to}")
    int deleteRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 区间内全部快照，按 (trade_date, instrument_id) 升序；组合聚合与持仓变化都基于它。
     */
    @Select("""
            SELECT * FROM position_snapshot
            WHERE trade_date BETWEEN #{from} AND #{to}
            ORDER BY trade_date ASC, instrument_id ASC
            """)
    List<PositionSnapshot> selectRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 区间起点之前每个标的的最后一条快照——计算"期初持仓"，避免从 0 算起把已有仓位当成新开仓。
     */
    @Select("""
            SELECT DISTINCT ON (instrument_id) *
            FROM position_snapshot
            WHERE trade_date < #{from}
            ORDER BY instrument_id, trade_date DESC
            """)
    List<PositionSnapshot> selectLatestBefore(@Param("from") LocalDate from);

    @Select("SELECT max(trade_date) FROM position_snapshot")
    LocalDate findLatestDate();

    @Select("SELECT min(trade_date) FROM position_snapshot")
    LocalDate findEarliestDate();

    /**
     * 批量写入。分区间的重建可能一次写几千行，按批调用（服务层切 500 行一批）。
     */
    @Insert("""
            <script>
            INSERT INTO position_snapshot (trade_date, instrument_id, quantity, avg_cost, cost_value,
                                           close_price, market_value, unrealized_pnl, realized_pnl_cum,
                                           price_adjust, source_code)
            VALUES
            <foreach collection="rows" item="r" separator=",">
                (#{r.tradeDate}, #{r.instrumentId}, #{r.quantity}, #{r.avgCost}, #{r.costValue},
                 #{r.closePrice}, #{r.marketValue}, #{r.unrealizedPnl}, #{r.realizedPnlCum},
                 #{r.priceAdjust}, #{r.sourceCode})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<PositionSnapshot> rows);
}
