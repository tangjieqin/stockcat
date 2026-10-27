package com.stockcat.system;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务统计只记事实：没跑过是 UNKNOWN，不是"看起来正常"。
 */
class JobRunStatsTest {

    private final JobRunStats stats = new JobRunStats();

    @Test
    void neverRanIsUnknown() {
        Map<String, Object> view = stats.view(JobRunStats.HISTORY_SYNC);

        assertThat(view).containsEntry("status", "UNKNOWN")
                .containsEntry("attempts", 0L)
                .containsEntry("lastSuccessAt", null);
    }

    @Test
    void successRecordsSummaryAndTimestamp() {
        stats.recordSuccess(JobRunStats.POSITION_SNAPSHOT, "交易日 5 行 120");

        Map<String, Object> view = stats.view(JobRunStats.POSITION_SNAPSHOT);
        assertThat(view).containsEntry("status", "UP")
                .containsEntry("summary", "交易日 5 行 120")
                .containsEntry("successes", 1L);
        assertThat(view.get("lastSuccessAt")).isNotNull();
    }

    @Test
    void failureKeepsLastSuccessTimeButFlipsStatus() {
        stats.recordSuccess(JobRunStats.HISTORY_SYNC, "标的 3 成功 3");
        stats.recordFailure(JobRunStats.HISTORY_SYNC, "连接被拒绝");

        Map<String, Object> view = stats.view(JobRunStats.HISTORY_SYNC);
        assertThat(view).containsEntry("status", "FAILED")
                .containsEntry("lastError", "连接被拒绝")
                .containsEntry("consecutiveFailures", 1);
        // 上次成功时间是历史事实，失败不能把它抹掉
        assertThat(view.get("lastSuccessAt")).isNotNull();
    }

    @Test
    void successAfterFailureClearsErrorAndCounter() {
        stats.recordFailure(JobRunStats.INDICATOR_ALERT, "超时");
        stats.recordSuccess(JobRunStats.INDICATOR_ALERT, "规则 2 触发 0");

        Map<String, Object> view = stats.view(JobRunStats.INDICATOR_ALERT);
        assertThat(view).containsEntry("status", "UP")
                .containsEntry("consecutiveFailures", 0)
                .containsEntry("lastError", null);
    }

    @Test
    void asMapAlwaysHasFourJobs() {
        assertThat(stats.asMap()).containsOnlyKeys(JobRunStats.HISTORY_SYNC, JobRunStats.FUND_NAV_SYNC,
                JobRunStats.POSITION_SNAPSHOT, JobRunStats.INDICATOR_ALERT);
    }

    @Test
    void errorTextFallsBackToClassNameWhenMessageMissing() {
        assertThat(JobRunStats.errorText(new IllegalStateException((String) null)))
                .isEqualTo("IllegalStateException");
        assertThat(JobRunStats.errorText(new IllegalStateException("净值为空")))
                .isEqualTo("净值为空");
    }
}
