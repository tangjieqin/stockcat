package com.stockcat.mapper;

import com.stockcat.domain.KlineDaily;
import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface KlineDailyMapper {

    /**
     * 幂等写入：唯一键 (instrument_id, trade_date, adjust) 冲突时覆盖。
     * 前复权的历史值会随除权整体移动，因此重复同步必须覆盖旧值，不能只做增量。
     */
    @Insert("""
            INSERT INTO kline_daily (instrument_id, trade_date, adjust, open, high, low, close,
                                     volume, amount, change_percent, turnover_rate, source_code, fetched_at)
            VALUES (#{instrumentId}, #{tradeDate}, #{adjust}, #{open}, #{high}, #{low}, #{close},
                    #{volume}, #{amount}, #{changePercent}, #{turnoverRate}, #{sourceCode}, now())
            ON CONFLICT (instrument_id, trade_date, adjust) DO UPDATE SET
                open = EXCLUDED.open,
                high = EXCLUDED.high,
                low = EXCLUDED.low,
                close = EXCLUDED.close,
                volume = EXCLUDED.volume,
                amount = EXCLUDED.amount,
                change_percent = EXCLUDED.change_percent,
                turnover_rate = EXCLUDED.turnover_rate,
                source_code = EXCLUDED.source_code,
                fetched_at = now()
            """)
    int upsert(KlineDaily bar);

    /**
     * 取最近 limit 根（按 trade_date 倒序），由调用方反转成升序返回给前端。
     */
    @Select("""
            <script>
            SELECT * FROM kline_daily
            WHERE instrument_id = #{instrumentId} AND adjust = #{adjust}
            <if test="end != null">AND trade_date &lt;= #{end}</if>
            ORDER BY trade_date DESC
            LIMIT #{limit}
            </script>
            """)
    List<KlineDaily> selectRecent(@Param("instrumentId") Long instrumentId,
                                  @Param("adjust") String adjust,
                                  @Param("end") LocalDate end,
                                  @Param("limit") int limit);

    @Select("SELECT count(*) FROM kline_daily WHERE instrument_id = #{instrumentId} AND adjust = #{adjust}")
    long countByInstrument(@Param("instrumentId") Long instrumentId, @Param("adjust") String adjust);

    /**
     * 某口径下截至 end 的全部日线（升序）。持仓快照要用它算"当日收盘价"和"停牌日沿用前收"。
     * 只取 end 之前的，避免用未来价格算历史市值。
     */
    @Select("""
            SELECT * FROM kline_daily
            WHERE instrument_id = #{instrumentId} AND adjust = #{adjust} AND trade_date <= #{end}
            ORDER BY trade_date ASC
            """)
    List<KlineDaily> selectUpTo(@Param("instrumentId") Long instrumentId,
                                @Param("adjust") String adjust,
                                @Param("end") LocalDate end);

    /** 已落库的最新交易日；无数据返回 null。 */
    @Select("SELECT max(trade_date) FROM kline_daily WHERE instrument_id = #{instrumentId} AND adjust = #{adjust}")
    LocalDate findLatestTradeDate(@Param("instrumentId") Long instrumentId, @Param("adjust") String adjust);
}
