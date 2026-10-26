package com.stockcat.mapper;

import com.stockcat.domain.QuoteSnapshot;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface QuoteSnapshotMapper {

    @Insert("""
            INSERT INTO quote_snapshot (instrument_id, source_code, last_price, previous_close, change_percent,
                                        volume, turnover, market_time, collected_at, data_status)
            VALUES (#{instrumentId}, #{sourceCode}, #{lastPrice}, #{previousClose}, #{changePercent},
                    #{volume}, #{turnover}, #{marketTime}, now(), #{dataStatus})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(QuoteSnapshot snapshot);

    /**
     * 每个标的取最新一条快照（无快照的标的不出现在结果中）。
     */
    @Select("""
            <script>
            SELECT DISTINCT ON (instrument_id)
                   id, instrument_id, source_code, last_price, previous_close, change_percent,
                   volume, turnover, market_time, collected_at, data_status
            FROM quote_snapshot
            WHERE instrument_id IN
            <foreach collection="instrumentIds" item="iid" open="(" separator="," close=")">#{iid}</foreach>
            ORDER BY instrument_id, collected_at DESC
            </script>
            """)
    List<QuoteSnapshot> selectLatestByInstrumentIds(@Param("instrumentIds") List<Long> instrumentIds);
}
