package com.stockcat.collector;

import com.stockcat.service.KlineSyncService;
import com.stockcat.service.TradingCalendarService;
import com.stockcat.system.JobRunStats;
import com.stockcat.web.dto.KlineSyncBatchSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 历史日 K 收盘后同步（P1.2）。
 *
 * <p>与 P0 采集的隔离原则：本任务的任何失败只写日志与统计，不抛异常、不改变实时采集与告警的行为。
 * Python 适配器未启动时，这里每轮只记一次失败，Java 其余功能照常。
 *
 * <p>触发时间默认 15:35（A 股 15:00 收盘 + 5 分钟收盘价稳定余量），
 * 也可用 POST /api/v1/history/sync 手动补跑。
 */
@Component
public class KlineSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(KlineSyncScheduler.class);

    private final KlineSyncService syncService;
    private final TradingCalendarService calendarService;
    private final JobRunStats jobStats;
    private final boolean enabled;
    private final boolean syncOnStartup;
    private final int days;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public KlineSyncScheduler(KlineSyncService syncService,
                              TradingCalendarService calendarService,
                              JobRunStats jobStats,
                              @Value("${stockcat.history.sync.enabled:true}") boolean enabled,
                              @Value("${stockcat.history.sync.on-startup:false}") boolean syncOnStartup,
                              @Value("${stockcat.history.sync.days:120}") int days) {
        this.syncService = syncService;
        this.calendarService = calendarService;
        this.jobStats = jobStats;
        this.enabled = enabled;
        this.syncOnStartup = syncOnStartup;
        this.days = days;
    }

    @Scheduled(cron = "${stockcat.history.sync.cron:0 35 15 * * MON-FRI}", zone = "Asia/Shanghai")
    public void syncAfterClose() {
        if (!enabled) {
            return;
        }
        if (!calendarService.isTradingDay()) {
            // 非交易日没有新数据；幂等 UPSERT 也不会写坏，但没必要打上游
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("上一轮历史同步尚未结束，本轮跳过（防重入）");
            return;
        }
        try {
            // 统计由 KlineSyncService.syncAll 统一记录（手动触发也走同一条路径）
            KlineSyncBatchSummary summary = syncService.syncAll(days);
            log.info("收盘历史同步：标的 {} 成功 {} 失败 {} 跳过 {}，耗时 {}ms",
                    summary.instruments(), summary.succeeded(), summary.failed(),
                    summary.skipped(), summary.elapsedMs());
        } catch (Exception e) {
            // 兜底：绝不让历史行情同步影响 P0
            log.error("收盘历史同步异常（不影响实时采集与告警）: {}", e.getMessage(), e);
            jobStats.recordFailure(JobRunStats.HISTORY_SYNC, JobRunStats.errorText(e));
        } finally {
            running.set(false);
        }
    }

    /**
     * 可选：启动后补跑一次，用于「收盘时服务没开」的场景。默认关闭，避免每次重启都打上游。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (!enabled || !syncOnStartup || !calendarService.isTradingDay()) {
            return;
        }
        log.info("启动补跑历史同步（{} 根）", days);
        syncAfterClose();
    }
}
