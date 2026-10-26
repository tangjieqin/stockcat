package com.stockcat.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 指标类规则的评估结果（P1.6）。
 *
 * <p>为什么要逐条返回 `status` 而不是只给一个"触发了几条"：
 * 告警系统最常被问的就是"为什么没提醒我"。把「没交叉」「数据不足」「当天已触发过去重」
 * 「没同步 K 线」分开报，排查时不用猜；只回一个数字等于把排查工作推给用户。
 */
public record IndicatorAlertSummary(
        int instruments,
        int rules,
        int triggered,
        long elapsedMs,
        String adjust,
        List<RuleResult> results
) {

    /** 单条规则的评估结果。 */
    public record RuleResult(
            Long ruleId,
            String ruleName,
            String symbol,
            String ruleType,
            /** TRIGGERED / NOT_TRIGGERED / SKIPPED_NO_KLINES / SKIPPED_INSUFFICIENT_BARS / ERROR */
            String status,
            /** 参与判定的最新交易日；无 K 线时为 null */
            LocalDate tradeDate,
            /** 所需最少 K 线根数（行情类规则为 0） */
            int requiredBars,
            /** 实际参与判定的 K 线根数 */
            int availableBars,
            String message
    ) {
    }
}
