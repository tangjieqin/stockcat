package com.stockcat.service;

import com.stockcat.mapper.TradingCalendarMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/**
 * A 股交易日历与交易时段判断。
 *
 * 优先级：trading_calendar 表（有当日记录）> 回退规则。
 * 回退规则：周一至周五为交易日 + 配置的节假日覆盖（extra-holidays）。
 * 节假日数据的来源与年度更新方式见 README（P0 回退规则不识别法定调休，需人工维护表或配置）。
 */
@Service
public class TradingCalendarService {

    private static final Logger log = LoggerFactory.getLogger(TradingCalendarService.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final TradingCalendarMapper calendarMapper;
    /** 采集窗口，如 "09:25-11:35,12:55-15:10"：覆盖开收盘的宽松区间 */
    private final String collectWindows;
    private final Set<LocalDate> extraHolidays;
    private final Set<LocalDate> extraWorkdays;

    public TradingCalendarService(TradingCalendarMapper calendarMapper,
                                  @Value("${stockcat.calendar.collect-windows:09:25-11:35,12:55-15:10}") String collectWindows,
                                  @Value("${stockcat.calendar.extra-holidays:}") String extraHolidays,
                                  @Value("${stockcat.calendar.extra-workdays:}") String extraWorkdays) {
        this.calendarMapper = calendarMapper;
        this.collectWindows = collectWindows;
        this.extraHolidays = parseDates(extraHolidays);
        this.extraWorkdays = parseDates(extraWorkdays);
    }

    private static Set<LocalDate> parseDates(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<LocalDate> dates = new HashSet<>();
        for (String s : csv.split(",")) {
            if (!s.isBlank()) {
                dates.add(LocalDate.parse(s.trim()));
            }
        }
        return dates;
    }

    /** 是否交易日：表有记录按表，否则周末规则 + 覆盖配置。 */
    public boolean isTradingDay(LocalDate date) {
        Boolean fromTable = calendarMapper.isTradingDay(date);
        if (fromTable != null) {
            return fromTable;
        }
        if (extraHolidays.contains(date)) {
            return false;
        }
        if (extraWorkdays.contains(date)) {
            return true;
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    public boolean isTradingDay() {
        return isTradingDay(LocalDate.now(CN));
    }

    /**
     * 当前时刻（A 股时区）是否落在采集窗口内。
     */
    public boolean inCollectWindow(OffsetDateTime at) {
        LocalTime t = at.atZoneSameInstant(CN).toLocalTime();
        for (String window : collectWindows.split(",")) {
            String[] parts = window.trim().split("-");
            if (parts.length != 2) {
                continue;
            }
            LocalTime start = LocalTime.parse(parts[0].trim());
            LocalTime end = LocalTime.parse(parts[1].trim());
            if (!t.isBefore(start) && !t.isAfter(end)) {
                return true;
            }
        }
        return false;
    }

    public boolean inCollectWindow() {
        return inCollectWindow(OffsetDateTime.now(CN));
    }

    /** 调度器是否应在本时刻执行采集。 */
    public boolean shouldCollectNow() {
        return isTradingDay() && inCollectWindow();
    }

    public String describeConfig() {
        return "windows=" + collectWindows + ", extraHolidays=" + extraHolidays + ", extraWorkdays=" + extraWorkdays;
    }
}
