package com.stockcat.collector;

import com.stockcat.mapper.PositionSnapshotMapper;
import com.stockcat.service.PositionSnapshotService;
import com.stockcat.service.TradingCalendarService;
import com.stockcat.system.JobRunStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 收盘后生成持仓快照（P1.5）。
 *
 * <p>时间放在 15:45：15:35 同步日 K、15:40 评估指标告警，快照要在两者之后，
 * 才能拿到当天的收盘价。快照区间是"最近 N 天"而不是只写今天——
 * 改过流水（补录、删错单）之后，靠区间重建把历史一起纠回来，比只补今天可靠。
 *
 * <p>桌面端不常开，所以启动时补跑一次；已经是最新快照日就跳过，避免每次启动都打上游。
 */
@Component
public class PositionSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(PositionSnapshotJob.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final TradingCalendarService calendarService;
    private final PositionSnapshotService snapshotService;
    private final PositionSnapshotMapper snapshotMapper;
    private final JobRunStats jobStats;
    private final boolean enabled;
    private final int days;
    private final boolean onStartup;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public PositionSnapshotJob(TradingCalendarService calendarService,
                               PositionSnapshotService snapshotService,
                               PositionSnapshotMapper snapshotMapper,
                               JobRunStats jobStats,
                               @Value("${stockcat.position.snapshot.enabled:true}") boolean enabled,
                               @Value("${stockcat.position.snapshot.days:30}") int days,
                               @Value("${stockcat.position.snapshot.on-startup:true}") boolean onStartup) {
        this.calendarService = calendarService;
        this.snapshotService = snapshotService;
        this.snapshotMapper = snapshotMapper;
        this.jobStats = jobStats;
        this.enabled = enabled;
        this.days = Math.max(1, days);
        this.onStartup = onStartup;
    }

    @Scheduled(cron = "${stockcat.position.snapshot.cron:0 45 15 * * MON-FRI}", zone = "Asia/Shanghai")
    public void runAfterClose() {
        if (!enabled) {
            return;
        }
        if (!calendarService.isTradingDay()) {
            return;
        }
        rebuildRecent();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        if (!enabled || !onStartup) {
            return;
        }
        LocalDate latest = safeLatestDate();
        if (LocalDate.now(CN).equals(latest)) {
            log.debug("持仓快照已是今日（{}），启动补跑跳过", latest);
            return;
        }
        log.info("启动补跑持仓快照（最近 {} 天）", days);
        rebuildRecent();
    }

    private void rebuildRecent() {
        if (!running.compareAndSet(false, true)) {
            log.warn("上一轮持仓快照重建尚未结束，本轮跳过（防重入）");
            return;
        }
        try {
            LocalDate to = LocalDate.now(CN);
            PositionSnapshotService.RebuildResult result = snapshotService.rebuild(to.minusDays(days), to, true);
            log.info("持仓快照：{}~{} 交易日 {} 行 {} 提示 {} 耗时 {}ms",
                    result.from(), result.to(), result.tradeDates(), result.rows(),
                    result.warnings().size(), result.elapsedMs());
            result.warnings().forEach(w -> log.warn("持仓快照提示：{}", w));
            jobStats.recordSuccess(JobRunStats.POSITION_SNAPSHOT,
                    "%s~%s 交易日 %d 行 %d 提示 %d".formatted(
                            result.from(), result.to(), result.tradeDates(), result.rows(),
                            result.warnings().size()));
        } catch (Exception e) {
            // 快照失败不影响行情/告警；第二天会连同区间一起重建
            log.error("持仓快照生成失败: {}", e.getMessage(), e);
            jobStats.recordFailure(JobRunStats.POSITION_SNAPSHOT, JobRunStats.errorText(e));
        } finally {
            running.set(false);
        }
    }

    private LocalDate safeLatestDate() {
        try {
            return snapshotMapper.findLatestDate();
        } catch (Exception e) {
            log.warn("读取最新快照日失败，按无快照处理: {}", e.getMessage());
            return null;
        }
    }
}
