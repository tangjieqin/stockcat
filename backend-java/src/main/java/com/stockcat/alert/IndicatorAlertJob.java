package com.stockcat.alert;

import com.stockcat.service.IndicatorAlertService;
import com.stockcat.service.TradingCalendarService;
import com.stockcat.system.JobRunStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 指标类告警的收盘后评估任务（P1.6）。
 *
 * <p>时间点 15:40 是刻意的：15:35 先同步当日日 K（KlineSyncScheduler），5 分钟缓冲留给
 * Python 适配器与落库；如果同步失败，这里会如实报"K 线不足"，而不是拿昨天的数据冒充今天。
 *
 * <p>与盘中评估任务（AlertEvaluationJob）分开，是因为两者输入不同：
 * 盘中只有行情快照，收盘后才有完整日 K。混在一个任务里，必然要在里面写
 * "这个类型跳过、那个类型继续"的分支，反而更容易漏。
 */
@Component
public class IndicatorAlertJob {

    private static final Logger log = LoggerFactory.getLogger(IndicatorAlertJob.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final IndicatorAlertService indicatorAlertService;
    private final TradingCalendarService calendarService;
    private final JobRunStats jobStats;
    private final boolean enabled;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public IndicatorAlertJob(IndicatorAlertService indicatorAlertService,
                             TradingCalendarService calendarService,
                             JobRunStats jobStats,
                             @Value("${stockcat.alert.indicator.enabled:true}") boolean enabled) {
        this.indicatorAlertService = indicatorAlertService;
        this.calendarService = calendarService;
        this.jobStats = jobStats;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${stockcat.alert.indicator.cron:0 40 15 * * MON-FRI}")
    public void run() {
        if (!enabled) {
            return;
        }
        LocalDate today = LocalDate.now(CN);
        if (!calendarService.isTradingDay(today)) {
            log.debug("非交易日，跳过指标告警评估: {}", today);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("上一轮指标评估尚未结束，本轮跳过（防重入）");
            return;
        }
        try {
            var summary = indicatorAlertService.evaluateAll();
            String text = summary.rules() == 0
                    ? "没有启用中的指标规则"
                    : "标的 %d 规则 %d 触发 %d".formatted(
                            summary.instruments(), summary.rules(), summary.triggered());
            if (summary.rules() == 0) {
                log.debug("没有启用中的指标类规则，跳过");
            }
            jobStats.recordSuccess(JobRunStats.INDICATOR_ALERT, text);
        } catch (Exception e) {
            log.error("指标告警评估异常: {}", e.getMessage(), e);
            jobStats.recordFailure(JobRunStats.INDICATOR_ALERT, JobRunStats.errorText(e));
        } finally {
            running.set(false);
        }
    }
}
