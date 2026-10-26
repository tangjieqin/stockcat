package com.stockcat.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 交易日历与采集窗口判断的单元测试。
 */
class TradingCalendarServiceTest {

    private final com.stockcat.mapper.TradingCalendarMapper mapper =
            Mockito.mock(com.stockcat.mapper.TradingCalendarMapper.class);

    @Test
    void tableRecordTakesPriority() {
        // 表里说 2026-10-24（周六）是交易日 -> 按表
        when(mapper.isTradingDay(LocalDate.of(2026, 10, 24))).thenReturn(true);
        TradingCalendarService svc = new TradingCalendarService(mapper, "09:25-11:35,12:55-15:10", "", "");
        assertThat(svc.isTradingDay(LocalDate.of(2026, 10, 24))).isTrue();

        // 表里说工作日休市（节假日）-> 按表
        when(mapper.isTradingDay(LocalDate.of(2026, 10, 1))).thenReturn(false);
        assertThat(svc.isTradingDay(LocalDate.of(2026, 10, 1))).isFalse();
    }

    @Test
    void fallbackWeekendRule() {
        when(mapper.isTradingDay(Mockito.any())).thenReturn(null);
        TradingCalendarService svc = new TradingCalendarService(mapper, "09:25-11:35,12:55-15:10", "", "");

        assertThat(svc.isTradingDay(LocalDate.of(2026, 10, 24))).isFalse(); // 周六
        assertThat(svc.isTradingDay(LocalDate.of(2026, 10, 25))).isFalse(); // 周日
        assertThat(svc.isTradingDay(LocalDate.of(2026, 10, 26))).isTrue();  // 周一
    }

    @Test
    void fallbackWithHolidayAndWorkdayOverrides() {
        when(mapper.isTradingDay(Mockito.any())).thenReturn(null);
        TradingCalendarService svc = new TradingCalendarService(mapper, "09:25-11:35,12:55-15:10",
                "2026-10-26",   // 周一配置为假日
                "2026-10-31");  // 周六配置为调休工作日

        assertThat(svc.isTradingDay(LocalDate.of(2026, 10, 26))).isFalse();
        assertThat(svc.isTradingDay(LocalDate.of(2026, 10, 31))).isTrue();
    }

    @Test
    void collectWindowBoundaries() {
        TradingCalendarService svc = new TradingCalendarService(mapper, "09:25-11:35,12:55-15:10", "", "");

        assertThat(svc.inCollectWindow(at("2026-10-26T09:25:00+08:00"))).isTrue();  // 开盘边界含
        assertThat(svc.inCollectWindow(at("2026-10-26T09:24:59+08:00"))).isFalse();
        assertThat(svc.inCollectWindow(at("2026-10-26T11:35:00+08:00"))).isTrue();  // 午盘结束含
        assertThat(svc.inCollectWindow(at("2026-10-26T12:00:00+08:00"))).isFalse(); // 午休
        assertThat(svc.inCollectWindow(at("2026-10-26T15:10:00+08:00"))).isTrue();  // 收盘窗口含
        assertThat(svc.inCollectWindow(at("2026-10-26T15:11:00+08:00"))).isFalse();
        assertThat(svc.inCollectWindow(at("2026-10-26T23:00:00+08:00"))).isFalse();
    }

    private static OffsetDateTime at(String iso) {
        return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime();
    }
}
