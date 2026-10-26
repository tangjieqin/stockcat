package com.stockcat.collector;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 采集器运行统计（内存态，进程重启清零）。
 * 供 /api/v1/system/status 展示最近成功时间、失败计数等。
 */
@Component
public class CollectorStats {

    private final AtomicLong attemptCount = new AtomicLong();
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failedQuoteCount = new AtomicLong();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    private volatile OffsetDateTime lastAttemptAt;
    private volatile OffsetDateTime lastSuccessAt;
    private volatile String lastErrorCode;

    public void recordAttempt() {
        attemptCount.incrementAndGet();
        lastAttemptAt = OffsetDateTime.now();
    }

    public void recordSuccess(int quoteCount, int failedCount) {
        successCount.incrementAndGet();
        failedQuoteCount.addAndGet(failedCount);
        consecutiveFailures.set(0);
        lastSuccessAt = OffsetDateTime.now();
        lastErrorCode = failedCount > 0 ? "PARTIAL_FAILURE" : null;
    }

    public void recordFailure(String errorCode) {
        consecutiveFailures.incrementAndGet();
        lastErrorCode = errorCode;
    }

    public long getAttemptCount() {
        return attemptCount.get();
    }

    public long getSuccessCount() {
        return successCount.get();
    }

    public long getFailedQuoteCount() {
        return failedQuoteCount.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public OffsetDateTime getLastAttemptAt() {
        return lastAttemptAt;
    }

    public OffsetDateTime getLastSuccessAt() {
        return lastSuccessAt;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }
}
