package com.stockcat.mapper;

import com.stockcat.domain.Instrument;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InstrumentMapper {

    @Insert("""
            INSERT INTO instrument (market, symbol, name, instrument_type, currency, source_code, enabled, created_at, updated_at)
            VALUES (#{market}, #{symbol}, #{name}, #{instrumentType}, #{currency}, #{sourceCode}, TRUE, now(), now())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Instrument instrument);

    @Select("SELECT * FROM instrument WHERE market = #{market} AND symbol = #{symbol}")
    Instrument findByMarketAndSymbol(@Param("market") String market, @Param("symbol") String symbol);

    @Select("SELECT * FROM instrument WHERE id = #{id}")
    Instrument findById(@Param("id") Long id);

    @Select("""
            <script>
            SELECT * FROM instrument
            <where>
                <if test="market != null">AND market = #{market}</if>
                <if test="type != null">AND instrument_type = #{type}</if>
                <if test="keyword != null">AND (symbol ILIKE '%' || #{keyword} || '%' OR name ILIKE '%' || #{keyword} || '%')</if>
            </where>
            ORDER BY market, symbol
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Instrument> selectPage(@Param("market") String market, @Param("type") String type,
                                @Param("keyword") String keyword, @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT count(*) FROM instrument
            <where>
                <if test="market != null">AND market = #{market}</if>
                <if test="type != null">AND instrument_type = #{type}</if>
                <if test="keyword != null">AND (symbol ILIKE '%' || #{keyword} || '%' OR name ILIKE '%' || #{keyword} || '%')</if>
            </where>
            </script>
            """)
    long countPage(@Param("market") String market, @Param("type") String type, @Param("keyword") String keyword);
}
