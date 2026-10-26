package com.stockcat.mapper;

import com.stockcat.domain.AlertEvent;
import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface AlertEventMapper {

    /**
     * 去重键唯一约束 + ON CONFLICT DO NOTHING：并发/重复评估安全。
     * 返回 1 表示新事件写入（并回填生成的主键 id），0 表示重复被抑制。
     */
    @Insert("""
            INSERT INTO alert_event (rule_id, instrument_id, rule_name_snapshot, rule_type_snapshot,
                                     condition_params_snapshot, observed_price, observed_change_percent,
                                     observed_detail, quote_market_time, triggered_at, deduplication_key, created_at)
            VALUES (#{ruleId}, #{instrumentId}, #{ruleNameSnapshot}, #{ruleTypeSnapshot},
                    CAST(#{conditionParamsSnapshot} AS jsonb), #{observedPrice}, #{observedChangePercent},
                    #{observedDetail}::jsonb, #{quoteMarketTime}, #{triggeredAt}, #{deduplicationKey}, now())
            ON CONFLICT (deduplication_key) DO NOTHING
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AlertEvent event);

    @Select("""
            SELECT e.id, e.rule_id, e.instrument_id, e.rule_name_snapshot, e.rule_type_snapshot,
                   e.condition_params_snapshot::text AS condition_params_snapshot,
                   e.observed_price, e.observed_change_percent,
                   e.observed_detail::text AS observed_detail,
                   e.quote_market_time, e.triggered_at, e.deduplication_key,
                   i.symbol
            FROM alert_event e
            JOIN instrument i ON i.id = e.instrument_id
            WHERE e.id = #{id}
            """)
    AlertEvent findById(@Param("id") Long id);

    @Select("""
            <script>
            SELECT e.id, e.rule_id, e.instrument_id, e.rule_name_snapshot, e.rule_type_snapshot,
                   e.condition_params_snapshot::text AS condition_params_snapshot,
                   e.observed_price, e.observed_change_percent,
                   e.observed_detail::text AS observed_detail,
                   e.quote_market_time, e.triggered_at, e.deduplication_key,
                   i.symbol
            FROM alert_event e
            JOIN instrument i ON i.id = e.instrument_id
            <where>
                <if test="instrumentId != null">AND e.instrument_id = #{instrumentId}</if>
                <if test="ruleId != null">AND e.rule_id = #{ruleId}</if>
                <if test="from != null">AND e.triggered_at &gt;= #{from}</if>
                <if test="to != null">AND e.triggered_at &lt;= #{to}</if>
            </where>
            ORDER BY e.triggered_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AlertEvent> selectPage(@Param("instrumentId") Long instrumentId, @Param("ruleId") Long ruleId,
                                @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                                @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT count(*) FROM alert_event e
            <where>
                <if test="instrumentId != null">AND e.instrument_id = #{instrumentId}</if>
                <if test="ruleId != null">AND e.rule_id = #{ruleId}</if>
                <if test="from != null">AND e.triggered_at &gt;= #{from}</if>
                <if test="to != null">AND e.triggered_at &lt;= #{to}</if>
            </where>
            </script>
            """)
    long countPage(@Param("instrumentId") Long instrumentId, @Param("ruleId") Long ruleId,
                   @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
