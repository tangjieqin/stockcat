package com.stockcat.mapper;

import com.stockcat.domain.Trade;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface TradeMapper {

    @Insert("""
            INSERT INTO trade_record (instrument_id, direction, price, quantity, fee, trade_time, note, created_at, updated_at)
            VALUES (#{instrumentId}, #{direction}, #{price}, #{quantity}, #{fee}, #{tradeTime}, #{note}, now(), now())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Trade trade);

    @Select("""
            SELECT t.id, t.instrument_id, t.direction, t.price, t.quantity, t.fee, t.trade_time, t.note,
                   t.created_at, t.updated_at, i.symbol, i.name AS instrument_name
            FROM trade_record t
            JOIN instrument i ON i.id = t.instrument_id
            WHERE t.id = #{id}
            """)
    Trade findById(@Param("id") Long id);

    @Select("""
            <script>
            SELECT t.id, t.instrument_id, t.direction, t.price, t.quantity, t.fee, t.trade_time, t.note,
                   t.created_at, t.updated_at, i.symbol, i.name AS instrument_name
            FROM trade_record t
            JOIN instrument i ON i.id = t.instrument_id
            <where>
                <if test="instrumentId != null">AND t.instrument_id = #{instrumentId}</if>
                <if test="direction != null">AND t.direction = #{direction}</if>
                <if test="from != null">AND t.trade_time &gt;= #{from}</if>
                <if test="to != null">AND t.trade_time &lt;= #{to}</if>
            </where>
            ORDER BY t.trade_time DESC, t.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Trade> selectPage(@Param("instrumentId") Long instrumentId, @Param("direction") String direction,
                           @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                           @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT count(*) FROM trade_record t
            <where>
                <if test="instrumentId != null">AND t.instrument_id = #{instrumentId}</if>
                <if test="direction != null">AND t.direction = #{direction}</if>
                <if test="from != null">AND t.trade_time &gt;= #{from}</if>
                <if test="to != null">AND t.trade_time &lt;= #{to}</if>
            </where>
            </script>
            """)
    long countPage(@Param("instrumentId") Long instrumentId, @Param("direction") String direction,
                   @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** 单标的全部流水，按成交时间升序（重放顺序，同时间按 id） */
    @Select("""
            SELECT t.*, i.symbol, i.name AS instrument_name
            FROM trade_record t
            JOIN instrument i ON i.id = t.instrument_id
            WHERE t.instrument_id = #{instrumentId}
            ORDER BY t.trade_time ASC, t.id ASC
            """)
    List<Trade> selectByInstrumentOrderByTime(@Param("instrumentId") Long instrumentId);

    @Select("SELECT DISTINCT instrument_id FROM trade_record ORDER BY instrument_id")
    List<Long> selectDistinctInstrumentIds();

    /** 全量流水（升序），持仓快照重建时一次读入，避免 N 次单标的查询。 */
    @Select("""
            SELECT t.*, i.symbol, i.name AS instrument_name
            FROM trade_record t
            JOIN instrument i ON i.id = t.instrument_id
            ORDER BY t.trade_time ASC, t.id ASC
            """)
    List<Trade> selectAllOrderByTime();

    /** 某标的在 [from, to] 内的成交笔数（持仓变化表用，说明变化是交易造成还是价格造成）。 */
    @Select("""
            SELECT count(*) FROM trade_record
            WHERE instrument_id = #{instrumentId} AND trade_time >= #{from} AND trade_time <= #{to}
            """)
    long countInRange(@Param("instrumentId") Long instrumentId,
                      @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /**
     * 截至某时点的净投入资金 = Σ 买入(金额+费用) − Σ 卖出(金额−费用)。
     *
     * <p>用来算"简单收益"：总盈亏 = 当前市值 − 净投入。它与"浮动盈亏 + 累计已实现"在数学上等价
     * （两者都基于同一套摊薄成本），但直接给净投入能让收益率有个说得通的分母。
     */
    @Select("""
            SELECT COALESCE(SUM(CASE WHEN direction = 'BUY'
                                     THEN price * quantity + fee
                                     ELSE -(price * quantity - fee) END), 0)
            FROM trade_record
            WHERE trade_time <= #{until}
            """)
    BigDecimal netInvestedUntil(@Param("until") OffsetDateTime until);

    @Delete("DELETE FROM trade_record WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
