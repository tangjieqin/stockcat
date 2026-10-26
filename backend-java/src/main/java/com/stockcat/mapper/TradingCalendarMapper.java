package com.stockcat.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface TradingCalendarMapper {

    /**
     * 查询某日是否交易日；无记录返回 null（调用方回退到周末规则）。
     * P0 仅覆盖 CN_A。
     */
    @Select("SELECT is_trading_day FROM trading_calendar WHERE market = 'CN_A' AND trade_date = #{date}")
    Boolean isTradingDay(@Param("date") LocalDate date);
}
