package com.stockcat.collector;

import com.stockcat.service.FundReferenceService;
import com.stockcat.service.KlineSyncService;
import com.stockcat.service.TradingCalendarService;
import com.stockcat.system.JobRunStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基金净值晚间同步（P1.4）。
 *
 * <p>为什么放在 21:30 而不是收盘后 15:45：实测 T 日净值当晚才公布
 * （2026-10-26 22:40 查到 510300 当日净值 4.1797，而盘中/刚收盘时还没有）。
 * 21:30 取一次能拿到当天净值；万一还没出，第二天窗口内的同步会自动补上
 * （每次都拉最近 N 期，UPSERT 是幂等的）。
 *
 * <p>启动补跑默认开启：桌面端常常白天才开机，否则 LOF 的净值基准永远停在旧日期。
 *
 * <p>失败隔离：净值源（Python）挂了只记日志，IOPV 与实时行情不受影响。
 */
@Component
public class FundNavSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(FundNavSyncScheduler.class);

    private final FundReferenceService fundService;
    private final KlineSyncService klineSyncService;
    private final TradingCalendarService calendarService;
    private final JobRunStats jobStats;
    private final boolean enabled;
    private final boolean syncOnStartup;
    private final int days;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public FundNavSyncScheduler(FundReferenceService fundService,
                                KlineSyncService klineSyncService,
                                TradingCalendarService calendarService,
                                JobRunStats jobStats,
                                @Value("${stockcat.fund.nav.enabled:true}") boolean enabled,
                                @Value("${stockcat.fund.nav.on-startup:true}") boolean syncOnStartup,
                                @Value("${stockcat.fund.nav.days:30}") int days) {
        this.fundService = fundService;
        this.klineSyncService = klineSyncService;
        this.calendarService = calendarService;
        this.jobStats = jobStats;
        this.enabled = enabled;
        this.syncOnStartup = syncOnStartup;
        this.days = days;
    }

    @Scheduled(cron = "${stockcat.fund.nav.cron:0 30 21 * * MON-FRI}", zone = "Asia/Shanghai")
    public void syncAfterNavPublished() {
        if (!enabled) {
            return;
        }
        if (!calendarService.isTradingDay()) {
            return;
        }
        runSync();
    }

    /**
     * 启动补跑：桌面端通常不是常开，缺一晚的净值靠这次补上。默认开启。
     * 不判断是否交易日——周末/节假日启动同样需要把已完成的历史净值补进库。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (!enabled || !syncOnStartup) {
            return;
        }
        log.info("启动补跑基金净值（{} 期）", days);
        runSync();
    }

    private void runSync() {
        if (!running.compareAndSet(false, true)) {
            log.warn("上一轮净值同步尚未结束，本轮跳过（防重入）");
            return;
        }
        try {
            List<Long> ids = klineSyncService.scopeInstrumentIds();
            FundReferenceService.BatchRefresh summary = fundService.refreshAll(ids, days);
            String text = "范围内标的 %d，基金 %d，净值入库 %d 期，失败 %d".formatted(
                    ids.size(), summary.instruments(), summary.navWritten(), summary.failed());
            log.info("晚间净值同步：{}，耗时 {}ms", text, summary.elapsedMs());
            jobStats.recordSuccess(JobRunStats.FUND_NAV_SYNC, text);
        } catch (Exception e) {
            log.error("净值同步异常（不影响实时行情与 IOPV）: {}", e.getMessage(), e);
            jobStats.recordFailure(JobRunStats.FUND_NAV_SYNC, JobRunStats.errorText(e));
        } finally {
            running.set(false);
        }
    }
}
