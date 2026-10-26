package com.stockcat.mapper;

import com.stockcat.domain.AlertRule;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AlertRuleMapper {

    @Insert("""
            INSERT INTO alert_rule (name, instrument_id, rule_type, params, cooldown_minutes, enabled, created_at, updated_at)
            VALUES (#{name}, #{instrumentId}, #{ruleType}, CAST(#{paramsJson} AS jsonb), #{cooldownMinutes},
                    #{enabled}, now(), now())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AlertRule rule);

    @Select("""
            SELECT r.id, r.name, r.instrument_id, r.rule_type, r.params::text AS params_json,
                   r.cooldown_minutes, r.enabled, r.last_triggered_at, r.created_at, r.updated_at,
                   i.symbol, i.name AS instrument_name
            FROM alert_rule r
            JOIN instrument i ON i.id = r.instrument_id
            WHERE r.id = #{id}
            """)
    AlertRule findById(@Param("id") Long id);

    @Select("""
            <script>
            SELECT r.id, r.name, r.instrument_id, r.rule_type, r.params::text AS params_json,
                   r.cooldown_minutes, r.enabled, r.last_triggered_at, r.created_at, r.updated_at,
                   i.symbol, i.name AS instrument_name
            FROM alert_rule r
            JOIN instrument i ON i.id = r.instrument_id
            <where>
                <if test="instrumentId != null">AND r.instrument_id = #{instrumentId}</if>
                <if test="enabled != null">AND r.enabled = #{enabled}</if>
            </where>
            ORDER BY r.created_at DESC, r.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AlertRule> selectPage(@Param("instrumentId") Long instrumentId, @Param("enabled") Boolean enabled,
                               @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT count(*) FROM alert_rule r
            <where>
                <if test="instrumentId != null">AND r.instrument_id = #{instrumentId}</if>
                <if test="enabled != null">AND r.enabled = #{enabled}</if>
            </where>
            </script>
            """)
    long countPage(@Param("instrumentId") Long instrumentId, @Param("enabled") Boolean enabled);

    @Select("""
            SELECT r.*, r.params::text AS params_json FROM alert_rule r
            WHERE r.enabled = TRUE ORDER BY r.id
            """)
    List<AlertRule> selectAllEnabled();

    @Update("""
            <script>
            UPDATE alert_rule
            <set>
                updated_at = now()
                <if test="name != null">, name = #{name}</if>
                <if test="ruleType != null">, rule_type = #{ruleType}</if>
                <if test="paramsJson != null">, params = CAST(#{paramsJson} AS jsonb)</if>
                <if test="cooldownMinutes != null">, cooldown_minutes = #{cooldownMinutes}</if>
                <if test="enabled != null">, enabled = #{enabled}</if>
            </set>
            WHERE id = #{id}
            </script>
            """)
    int updateFields(@Param("id") Long id, @Param("name") String name, @Param("ruleType") String ruleType,
                     @Param("paramsJson") String paramsJson,
                     @Param("cooldownMinutes") Integer cooldownMinutes, @Param("enabled") Boolean enabled);

    @Update("UPDATE alert_rule SET last_triggered_at = #{at}, updated_at = now() WHERE id = #{id}")
    int updateLastTriggered(@Param("id") Long id, @Param("at") java.time.OffsetDateTime at);

    @Delete("DELETE FROM alert_rule WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
