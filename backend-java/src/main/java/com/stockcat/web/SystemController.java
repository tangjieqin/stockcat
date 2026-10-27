package com.stockcat.web;

import com.stockcat.assistant.AssistantClient;
import com.stockcat.collector.CollectorStats;
import com.stockcat.service.IndexService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统状态，对应 docs/API.md §9。
 * 不返回密码、token、环境变量值或堆栈。
 */
@RestController
@RequestMapping("/api/v1")
public class SystemController {

    private final CollectorStats collectorStats;
    private final IndexService indexService;
    private final DataSource dataSource;
    private final AssistantClient assistantClient;

    public SystemController(CollectorStats collectorStats,
                            IndexService indexService,
                            @Autowired(required = false) DataSource dataSource,
                            AssistantClient assistantClient) {
        this.collectorStats = collectorStats;
        this.indexService = indexService;
        this.dataSource = dataSource;
        this.assistantClient = assistantClient;
    }

    @GetMapping("/system/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", "UP");
        body.put("database", checkDatabase());

        Map<String, Object> quoteCollector = new LinkedHashMap<>();
        boolean degraded = collectorStats.getConsecutiveFailures() >= 3;
        boolean everAttempted = collectorStats.getLastAttemptAt() != null;
        String status = !everAttempted ? "UNKNOWN" : degraded ? "DEGRADED" : "UP";
        quoteCollector.put("status", status);
        quoteCollector.put("lastSuccessAt", collectorStats.getLastSuccessAt());
        quoteCollector.put("lastAttemptAt", collectorStats.getLastAttemptAt());
        quoteCollector.put("lastErrorCode", collectorStats.getLastErrorCode());
        quoteCollector.put("consecutiveFailures", collectorStats.getConsecutiveFailures());
        quoteCollector.put("attemptCount", collectorStats.getAttemptCount());
        quoteCollector.put("successCount", collectorStats.getSuccessCount());
        body.put("quoteCollector", quoteCollector);

        body.put("indicesConfigured", indexService.definitions().size());

        // Python 服务（历史行情 + AI）：不可用时 DOWN 并带原因，绝不让整页 500。
        // 注意这里是一次真实探活，Python 挂起时本接口最多多等 3s——状态页可以接受，
        // 采集与告警都在别的线程，不受影响。
        body.put("pythonService", assistantClient.pythonService());
        return body;
    }

    private String checkDatabase() {
        if (dataSource == null) {
            return "NOT_CONFIGURED";
        }
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2) ? "UP" : "DOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }
}
