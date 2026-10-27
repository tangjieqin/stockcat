package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.Instrument;
import com.stockcat.domain.KlineDaily;
import com.stockcat.history.DailyBar;
import com.stockcat.history.HistoryDataClient;
import com.stockcat.history.HistorySourceException;
import com.stockcat.history.KlineFetch;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.mapper.KlineDailyMapper;
import com.stockcat.mapper.MonitorTargetMapper;
import com.stockcat.mapper.WatchlistMapper;
import com.stockcat.system.JobRunStats;
import com.stockcat.web.dto.KlineSyncSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P1.2 验收单元测试（不连数据库、不连 Python）：
 * 收盘判定、未收盘/负价拒写、幂等写入次数、上游不可用时的失败隔离。
 */
class KlineSyncServiceTest {

    private KlineDailyMapper klineMapper;
    private HistoryDataClient client;
    private TradingCalendarService calendar;
    private InstrumentMapper instrumentMapper;
    private JobRunStats jobStats;
    private KlineSyncService service;

    @BeforeEach
    void setup() {
        klineMapper = Mockito.mock(KlineDailyMapper.class);
        client = Mockito.mock(HistoryDataClient.class);
        calendar = Mockito.mock(TradingCalendarService.class);
        instrumentMapper = Mockito.mock(InstrumentMapper.class);
        jobStats = new JobRunStats();
        service = new KlineSyncService(klineMapper, client, calendar, instrumentMapper,
                Mockito.mock(WatchlistMapper.class), Mockito.mock(MonitorTargetMapper.class),
                Mockito.mock(PositionService.class), jobStats, "15:05");
    }

    @Test
    void syncAll_recordsJobStatsSoTheStatusPageIsNotBlind() {
        // 同步范围为空也算"跑过一次"：系统状态页应从 UNKNOWN 变成 UP，
        // 而不是在用户刚点完手动同步后还写着"本进程未运行"
        service.syncAll(120);

        assertThat(jobStats.view(JobRunStats.HISTORY_SYNC))
                .containsEntry("status", "UP")
                .containsEntry("attempts", 1L)
                .containsEntry("summary", "标的 0 成功 0 失败 0 跳过 0");
    }

    // ---------- 过滤规则 ----------

    @Test
    void filter_rejectsUnclosedBarAndNonPositivePrice() {
        LocalDate lastClosed = LocalDate.parse("2026-10-23");
        DailyBar good = bar("2026-10-23", 10.0, 11.0, 9.5, 10.5);
        DailyBar unclosed = bar("2026-10-26", 10.0, 11.0, 9.5, 10.5);
        DailyBar negative = bar("2026-10-22", -312.68, -300.0, -320.0, -310.0);
        DailyBar missingDate = new DailyBar(null, 10.0, 11.0, 9.5, 10.5, 1.0, 1.0, null, null);

        KlineSyncService.FilterOutcome outcome =
                service.filter(List.of(good, unclosed, negative, missingDate), lastClosed);

        assertThat(outcome.accepted()).containsExactly(good);
        assertThat(outcome.rejectedUnclosed()).isEqualTo(1);
        assertThat(outcome.rejectedInvalid()).isEqualTo(2);
    }

    @Test
    void filter_rejectsHighLowerThanLow() {
        LocalDate lastClosed = LocalDate.parse("2026-10-23");
        DailyBar broken = bar("2026-10-22", 10.0, 9.0, 11.0, 10.0);

        KlineSyncService.FilterOutcome outcome = service.filter(List.of(broken), lastClosed);

        assertThat(outcome.accepted()).isEmpty();
        assertThat(outcome.rejectedInvalid()).isEqualTo(1);
    }

    @Test
    void filter_keepsBarWithNullVolume() {
        LocalDate lastClosed = LocalDate.parse("2026-10-23");
        DailyBar suspended = new DailyBar(LocalDate.parse("2026-10-22"), 10.0, 10.0, 10.0, 10.0,
                null, null, null, null);

        assertThat(service.filter(List.of(suspended), lastClosed).accepted()).hasSize(1);
    }

    // ---------- 收盘判定 ----------

    @Test
    void lastCompletedTradingDate_beforeCloseUsesPreviousTradingDay() {
        when(calendar.isTradingDay(LocalDate.parse("2026-10-26"))).thenReturn(true);
        when(calendar.isTradingDay(LocalDate.parse("2026-10-23"))).thenReturn(true);

        // 周一 10:00 盘中：当日 K 线未收，只能用上周五
        assertThat(service.lastCompletedTradingDate(OffsetDateTime.parse("2026-10-26T10:00:00+08:00")))
                .isEqualTo(LocalDate.parse("2026-10-23"));
        // 同一天收盘后：当日可用
        assertThat(service.lastCompletedTradingDate(OffsetDateTime.parse("2026-10-26T15:30:00+08:00")))
                .isEqualTo(LocalDate.parse("2026-10-26"));
    }

    @Test
    void lastCompletedTradingDate_onWeekendFallsBackToFriday() {
        // 周六、周日都不是交易日，周五是
        when(calendar.isTradingDay(LocalDate.parse("2026-10-23"))).thenReturn(true);

        assertThat(service.lastCompletedTradingDate(OffsetDateTime.parse("2026-10-25T12:00:00+08:00")))
                .isEqualTo(LocalDate.parse("2026-10-23"));
    }

    // ---------- 同步行为 ----------

    @Test
    void sync_writesEveryAcceptedBar() {
        Instrument instrument = instrument(1L, "600519");
        when(instrumentMapper.findById(1L)).thenReturn(instrument);
        when(calendar.isTradingDay(any(LocalDate.class))).thenReturn(true);

        // 用「已过去至少 4 天」的日期，避免测试在盘中运行时被「未收盘」规则拦掉
        LocalDate base = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(6);
        List<DailyBar> bars = List.of(
                bar(base.toString(), 10.0, 11.0, 9.5, 10.5),
                bar(base.plusDays(1).toString(), 10.5, 11.5, 10.0, 11.0),
                bar(base.plusDays(2).toString(), 11.0, 12.0, 10.8, 11.8));
        when(client.fetch(eq("1.600519"), eq("qfq"), eq(120), any()))
                .thenReturn(new KlineFetch("eastmoney-push2his", "1.600519", "600519", "qfq",
                        120, OffsetDateTime.now(), 3, 2, bars));

        KlineSyncSummary summary = service.syncInstrument(1L, 120, "qfq");

        assertThat(summary.status()).isEqualTo("SUCCESS");
        assertThat(summary.accepted()).isEqualTo(3);
        assertThat(summary.skippedUpstream()).isEqualTo(2);
        assertThat(summary.source()).isEqualTo("eastmoney-push2his");

        ArgumentCaptor<KlineDaily> captor = ArgumentCaptor.forClass(KlineDaily.class);
        verify(klineMapper, times(3)).upsert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(row -> {
            assertThat(row.getInstrumentId()).isEqualTo(1L);
            assertThat(row.getAdjust()).isEqualTo("qfq");
            assertThat(row.getSourceCode()).isEqualTo("eastmoney-push2his");
        });
    }

    @Test
    void sync_pythonDownReturnsFailedWithoutThrowing() {
        when(instrumentMapper.findById(1L)).thenReturn(instrument(1L, "600519"));
        when(calendar.isTradingDay(any(LocalDate.class))).thenReturn(true);
        when(client.fetch(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new HistorySourceException("DATA_SOURCE_UNAVAILABLE", "连接被拒绝"));

        KlineSyncSummary summary = service.syncInstrument(1L, 120, "qfq");

        assertThat(summary.status()).isEqualTo("FAILED");
        assertThat(summary.code()).isEqualTo("DATA_SOURCE_UNAVAILABLE");
        verifyNoInteractions(klineMapper);
    }

    @Test
    void sync_emptyUpstreamIsFailedNotSuccessWithZeroBars() {
        when(instrumentMapper.findById(1L)).thenReturn(instrument(1L, "600519"));
        when(calendar.isTradingDay(any(LocalDate.class))).thenReturn(true);
        when(client.fetch(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new KlineFetch("eastmoney-push2his", "1.600519", "600519", "qfq",
                        120, OffsetDateTime.now(), 0, 0, List.of()));

        KlineSyncSummary summary = service.syncInstrument(1L, 120, "qfq");

        // 上游「成功返回 0 条」必须当失败，不能伪装成同步成功
        assertThat(summary.status()).isEqualTo("FAILED");
        assertThat(summary.code()).isEqualTo("NO_DATA");
    }

    @Test
    void sync_unsupportedSymbolIsSkippedWithoutCallingUpstream() {
        when(instrumentMapper.findById(9L)).thenReturn(instrument(9L, "832000"));

        KlineSyncSummary summary = service.syncInstrument(9L, 120, "qfq");

        assertThat(summary.status()).isEqualTo("SKIPPED");
        assertThat(summary.code()).isEqualTo("UNSUPPORTED_SYMBOL");
        verifyNoInteractions(client);
    }

    @Test
    void sync_missingInstrumentThrows404() {
        when(instrumentMapper.findById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.syncInstrument(404L, 120, "qfq"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("标的不存在");
    }

    @Test
    void normalizeAdjust_rejectsUnknownValue() {
        assertThat(KlineSyncService.normalizeAdjust("QFQ")).isEqualTo("qfq");
        assertThat(KlineSyncService.normalizeAdjust(null)).isEqualTo("qfq");
        assertThatThrownBy(() -> KlineSyncService.normalizeAdjust("weekly"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("adjust");
    }

    @Test
    void clampLimit_capsAt500() {
        assertThat(KlineSyncService.clampLimit(0)).isEqualTo(1);
        assertThat(KlineSyncService.clampLimit(120)).isEqualTo(120);
        assertThat(KlineSyncService.clampLimit(9999)).isEqualTo(500);
    }

    // ---------- helpers ----------

    private static DailyBar bar(String date, double open, double high, double low, double close) {
        return new DailyBar(LocalDate.parse(date), open, high, low, close, 1000.0, 1000000.0, 0.5, 0.3);
    }

    private static Instrument instrument(Long id, String symbol) {
        Instrument instrument = new Instrument();
        instrument.setId(id);
        instrument.setSymbol(symbol);
        instrument.setMarket("CN_A");
        instrument.setInstrumentType("STOCK");
        instrument.setName("测试标的");
        instrument.setCurrency("CNY");
        instrument.setEnabled(true);
        return instrument;
    }
}
