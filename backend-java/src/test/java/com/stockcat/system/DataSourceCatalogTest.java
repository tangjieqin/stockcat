package com.stockcat.system;

import com.stockcat.collector.CollectorStats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据源目录：地址、开关、频率、上次结果都要有，且**不含凭据**。
 */
class DataSourceCatalogTest {

    private DataSourceCatalog catalog(CollectorStats quoteStats, JobRunStats jobStats) {
        return new DataSourceCatalog(quoteStats, jobStats,
                "https://push2.eastmoney.com", 15, "09:25-11:35,12:55-15:10", true,
                "https://qt.gtimg.cn",
                "http://127.0.0.1:8100", true, "0 35 15 * * MON-FRI",
                "http://127.0.0.1:8100", true, "0 30 21 * * MON-FRI");
    }

    @Test
    void listsFourSourcesInDisplayOrder() {
        List<Map<String, Object>> sources = catalog(new CollectorStats(), new JobRunStats()).list();

        assertThat(sources).extracting(item -> item.get("key"))
                .containsExactly("quote", "fund-quote", "history", "fund-nav");
    }

    @Test
    void quoteStatsComeFromCollectorStats() {
        CollectorStats quoteStats = new CollectorStats();
        quoteStats.recordAttempt();
        quoteStats.recordSuccess(3, 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) catalog(quoteStats, new JobRunStats())
                .list().get(0).get("stats");

        assertThat(stats).containsEntry("status", "UP")
                .containsEntry("successes", 1L)
                .containsEntry("summary", "成功 1 次、失败 1 只次");
        assertThat(stats.get("lastSuccessAt")).isNotNull();
    }

    @Test
    void quoteBeforeFirstAttemptIsUnknown() {
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) catalog(new CollectorStats(), new JobRunStats())
                .list().get(0).get("stats");

        assertThat(stats).containsEntry("status", "UNKNOWN");
    }

    @Test
    void syncJobsReportUnknownUntilTheyRun() {
        @SuppressWarnings("unchecked")
        Map<String, Object> history = (Map<String, Object>) catalog(new CollectorStats(), new JobRunStats())
                .list().get(2).get("stats");

        assertThat(history).containsEntry("status", "UNKNOWN");
    }

    @Test
    void syncJobPicksUpRecordedRun() {
        JobRunStats jobStats = new JobRunStats();
        jobStats.recordSuccess(JobRunStats.FUND_NAV_SYNC, "净值入库 30 期");

        @SuppressWarnings("unchecked")
        Map<String, Object> nav = (Map<String, Object>) catalog(new CollectorStats(), jobStats)
                .list().get(3).get("stats");

        assertThat(nav).containsEntry("status", "UP")
                .containsEntry("summary", "净值入库 30 期");
    }

    @Test
    void fundQuoteHasNoStatsButSaysWhy() {
        Map<String, Object> fundQuote = catalog(new CollectorStats(), new JobRunStats()).list().get(1);

        assertThat(fundQuote.get("stats")).isNull();
        assertThat(fundQuote.get("note")).asString().contains("无独立运行统计");
    }

    @Test
    void neverExposesCredentials() {
        String dump = catalog(new CollectorStats(), new JobRunStats()).list().toString().toLowerCase();

        assertThat(dump).doesNotContain("password", "secret", "token", "apikey", "send-key");
    }

    @Test
    void collectorStatusOnlyDegradesAfterThreeFailures() {
        CollectorStats quoteStats = new CollectorStats();

        assertThat(DataSourceCatalog.collectorStatus(quoteStats)).isEqualTo("UP");
        quoteStats.recordFailure("TIMEOUT");
        quoteStats.recordFailure("TIMEOUT");
        assertThat(DataSourceCatalog.collectorStatus(quoteStats)).isEqualTo("UP");
        quoteStats.recordFailure("TIMEOUT");
        assertThat(DataSourceCatalog.collectorStatus(quoteStats)).isEqualTo("DEGRADED");
    }
}
