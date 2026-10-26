package com.stockcat.mapper;

import com.stockcat.domain.FundReference;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * fund_reference 读写（P1.4）。
 *
 * <p>两条 UPSERT 分别负责盘中行情与晚间净值：都用 COALESCE，先写的字段不会被后来的空值覆盖。
 * 这样"先采到 IOPV、后来才补到净值"和"先有净值、后来才有 IOPV"两种顺序都不会互相抹掉。
 */
@Mapper
public interface FundReferenceMapper {

    @Insert("""
            INSERT INTO fund_reference (instrument_id, trade_date, price, iopv, iopv_time, market_source_code, fetched_at)
            VALUES (#{instrumentId}, #{tradeDate}, #{price}, #{iopv}, #{iopvTime}, #{marketSourceCode}, now())
            ON CONFLICT (instrument_id, trade_date) DO UPDATE SET
                price      = COALESCE(EXCLUDED.price, fund_reference.price),
                iopv       = COALESCE(EXCLUDED.iopv, fund_reference.iopv),
                iopv_time  = COALESCE(EXCLUDED.iopv_time, fund_reference.iopv_time),
                market_source_code = EXCLUDED.market_source_code,
                fetched_at = now()
            """)
    int upsertMarketData(FundReference row);

    @Insert("""
            INSERT INTO fund_reference (instrument_id, trade_date, nav, nav_date, nav_source_code, fetched_at)
            VALUES (#{instrumentId}, #{tradeDate}, #{nav}, #{navDate}, #{navSourceCode}, now())
            ON CONFLICT (instrument_id, trade_date) DO UPDATE SET
                nav        = COALESCE(EXCLUDED.nav, fund_reference.nav),
                nav_date   = COALESCE(EXCLUDED.nav_date, fund_reference.nav_date),
                nav_source_code = EXCLUDED.nav_source_code,
                fetched_at = now()
            """)
    int upsertNav(FundReference row);

    @Select("""
            <script>
            SELECT DISTINCT ON (instrument_id) *
            FROM fund_reference
            WHERE instrument_id IN
            <foreach collection="instrumentIds" item="iid" open="(" separator="," close=")">#{iid}</foreach>
            ORDER BY instrument_id, trade_date DESC
            </script>
            """)
    List<FundReference> selectLatestByInstrumentIds(@Param("instrumentIds") List<Long> instrumentIds);

    /**
     * 每个标的最新一条「带净值日期」的记录。
     * 只认有 nav_date 的行：未标注日期的净值不能拿来算溢价（那是"看起来实时、其实不知道哪天"）。
     */
    @Select("""
            <script>
            SELECT DISTINCT ON (instrument_id) *
            FROM fund_reference
            WHERE nav IS NOT NULL AND nav_date IS NOT NULL
              AND instrument_id IN
            <foreach collection="instrumentIds" item="iid" open="(" separator="," close=")">#{iid}</foreach>
            ORDER BY instrument_id, nav_date DESC, trade_date DESC
            </script>
            """)
    List<FundReference> selectLatestDatedNavByInstrumentIds(@Param("instrumentIds") List<Long> instrumentIds);

    /** 某个标的最近 limit 个交易日（倒序）。 */
    @Select("""
            SELECT * FROM fund_reference
            WHERE instrument_id = #{instrumentId}
            ORDER BY trade_date DESC
            LIMIT #{limit}
            """)
    List<FundReference> selectHistory(@Param("instrumentId") Long instrumentId, @Param("limit") int limit);
}
