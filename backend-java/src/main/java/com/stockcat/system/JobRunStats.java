package com.stockcat.system;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时任务的运行统计（内存态，进程重启清零）。
 *
 * <p>为什么要有它：这些任务（历史同步、净值同步、持仓快照、指标告警）之前只写日志。
 * 用户问"今天同步跑了吗"，唯一的办法是翻日志文件——而桌面端往往根本没开日志重定向。
 *
 * <p><b>只记录事实</b>：跑过就是跑过、失败就带错误码，从未运行返回 {@code UNKNOWN}，
 * 不用"看起来正常"的默认值糊过去。重启后统计清零，这一点在页面上也写明。
 */
@Component
public class JobRunStats {

    public static final String HISTORY_SYNC = "history-sync";
    public static final String FUND_NAV_SYNC = "fund-nav-sync";
    public static final String POSITION_SNAPSHOT = "position-snapshot";
    public static final String INDICATOR_ALERT = "indicator-alert";

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public void recordSuccess(String job, String summary) {
        states.compute(job, (key, old) -> {
            State state = old == null ? new State() : old;
            state.attempts++;
            state.successes++;
            state.consecutiveFailures = 0;
            state.lastAttemptAt = OffsetDateTime.now();
            state.lastSuccessAt = state.lastAttemptAt;
            state.lastError = null;
            state.summary = summary;
            return state;
        });
    }

    public void recordFailure(String job, String error) {
        states.compute(job, (key, old) -> {
            State state = old == null ? new State() : old;
            state.attempts++;
            state.consecutiveFailures++;
            state.lastAttemptAt = OffsetDateTime.now();
            state.lastError = error;
            return state;
        });
    }

    /** 单个任务的状态视图；从未运行过时 status=UNKNOWN。 */
    public Map<String, Object> view(String job) {
        State state = states.get(job);
        Map<String, Object> out = new LinkedHashMap<>();
        if (state == null) {
            out.put("status", "UNKNOWN");
            out.put("lastAttemptAt", null);
            out.put("lastSuccessAt", null);
            out.put("lastError", null);
            out.put("consecutiveFailures", 0);
            out.put("summary", null);
            out.put("attempts", 0L);
            out.put("successes", 0L);
            return out;
        }
        out.put("status", state.consecutiveFailures > 0 ? "FAILED" : "UP");
        out.put("lastAttemptAt", state.lastAttemptAt);
        out.put("lastSuccessAt", state.lastSuccessAt);
        out.put("lastError", state.lastError);
        out.put("consecutiveFailures", state.consecutiveFailures);
        out.put("summary", state.summary);
        out.put("attempts", state.attempts);
        out.put("successes", state.successes);
        return out;
    }

    /** 异常 → 一行可读文案：消息可能为空，退回类名，避免状态页显示 "null"。 */
    public static String errorText(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** 四个任务一起返回，供 /system/status 使用。 */
    public Map<String, Object> asMap() {        Map<String, Object> out = new LinkedHashMap<>();
        out.put(HISTORY_SYNC, view(HISTORY_SYNC));
        out.put(FUND_NAV_SYNC, view(FUND_NAV_SYNC));
        out.put(POSITION_SNAPSHOT, view(POSITION_SNAPSHOT));
        out.put(INDICATOR_ALERT, view(INDICATOR_ALERT));
        return out;
    }

    /** 可变状态，仅在 {@link ConcurrentHashMap#compute} 的同步块里改。 */
    private static final class State {
        private OffsetDateTime lastAttemptAt;
        private OffsetDateTime lastSuccessAt;
        private String lastError;
        private String summary;
        private int consecutiveFailures;
        private long attempts;
        private long successes;
    }
}
