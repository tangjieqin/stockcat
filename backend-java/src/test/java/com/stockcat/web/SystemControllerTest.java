package com.stockcat.web;

import com.stockcat.assistant.AssistantClient;
import com.stockcat.collector.CollectorStats;
import com.stockcat.service.IndexService;
import com.stockcat.system.DataSourceCatalog;
import com.stockcat.system.JobRunStats;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 系统状态接口：AI/Python 没起来时必须如实标 DOWN，而不是让整页报错。
 *
 * <p>这条边界很重要——AI 不在关键路径上，行情采集、告警、持仓都不该因为
 * Python 停了而在"系统状态"上看起来像整个应用坏了。
 */
class SystemControllerTest {

    private final JobRunStats jobRunStats = new JobRunStats();

    private SystemController controller(Map<String, Object> pythonService, List<Map<String, Object>> dataSources) {
        CollectorStats stats = new CollectorStats();
        IndexService indexService = Mockito.mock(IndexService.class);
        when(indexService.definitions()).thenReturn(List.of());
        AssistantClient client = Mockito.mock(AssistantClient.class);
        when(client.pythonService()).thenReturn(pythonService);
        DataSourceCatalog catalog = Mockito.mock(DataSourceCatalog.class);
        when(catalog.list()).thenReturn(dataSources);
        return new SystemController(stats, indexService, null, client, catalog, jobRunStats);
    }

    private SystemController controller(Map<String, Object> pythonService) {
        return controller(pythonService, List.of());
    }

    @Test
    void reportsPythonDownWithoutFailingApplication() {
        Map<String, Object> status = controller(Map.of("status", "DOWN", "error", "连接被拒绝")).status();

        assertThat(status).containsEntry("application", "UP");
        assertThat(status).containsEntry("database", "NOT_CONFIGURED");
        @SuppressWarnings("unchecked")
        Map<String, Object> python = (Map<String, Object>) status.get("pythonService");
        assertThat(python).containsEntry("status", "DOWN");
        assertThat(python).doesNotContainKey("agent");
    }

    @Test
    void reportsAgentSummaryWithoutLeakingKey() {
        Map<String, Object> status = controller(Map.of(
                "status", "UP",
                "baseUrl", "http://127.0.0.1:8100",
                "agent", Map.of(
                        "status", "UP",
                        "modelConfigured", true,
                        "model", "gpt-4o-mini",
                        "toolCount", 6))).status();

        @SuppressWarnings("unchecked")
        Map<String, Object> python = (Map<String, Object>) status.get("pythonService");
        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) python.get("agent");
        assertThat(agent).containsEntry("modelConfigured", true)
                .containsEntry("model", "gpt-4o-mini")
                .containsEntry("toolCount", 6);
        assertThat(status.toString().toLowerCase()).doesNotContain("apikey", "sk-");
    }

    @Test
    void quoteCollectorIsUnknownBeforeFirstAttempt() {
        Map<String, Object> status = controller(Map.of("status", "DOWN")).status();

        @SuppressWarnings("unchecked")
        Map<String, Object> collector = (Map<String, Object>) status.get("quoteCollector");
        assertThat(collector).containsEntry("status", "UNKNOWN");
    }

    @Test
    void exposesAllFourJobsEvenIfNeverRan() {
        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) controller(Map.of("status", "DOWN")).status().get("jobs");

        assertThat(jobs).containsOnlyKeys(JobRunStats.HISTORY_SYNC, JobRunStats.FUND_NAV_SYNC,
                JobRunStats.POSITION_SNAPSHOT, JobRunStats.INDICATOR_ALERT);
        @SuppressWarnings("unchecked")
        Map<String, Object> history = (Map<String, Object>) jobs.get(JobRunStats.HISTORY_SYNC);
        assertThat(history).containsEntry("status", "UNKNOWN");
    }

    @Test
    void jobStatusFlipsToFailedAfterException() {
        jobRunStats.recordFailure(JobRunStats.FUND_NAV_SYNC, "净值源未启动");

        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) controller(Map.of("status", "DOWN")).status().get("jobs");
        @SuppressWarnings("unchecked")
        Map<String, Object> nav = (Map<String, Object>) jobs.get(JobRunStats.FUND_NAV_SYNC);

        assertThat(nav).containsEntry("status", "FAILED")
                .containsEntry("consecutiveFailures", 1)
                .containsEntry("lastError", "净值源未启动");
    }

    @Test
    void dataSourcesArePassedThroughVerbatim() {
        Map<String, Object> source = Map.of("key", "quote", "name", "实时行情");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources =
                (List<Map<String, Object>>) controller(Map.of("status", "DOWN"), List.of(source))
                        .status().get("dataSources");

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0)).containsEntry("key", "quote");
    }
}
