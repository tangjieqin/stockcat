package com.stockcat.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.common.ApiException;
import com.stockcat.web.dto.FundPremiumResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1.5 溢价类规则：阈值含边界、基准缺失不触发、净值基准过旧不触发。
 */
class PremiumEvaluatorTest {

    private final ObjectMapper om = new ObjectMapper();
    private final PremiumAboveEvaluator above = new PremiumAboveEvaluator();
    private final PremiumBelowEvaluator below = new PremiumBelowEvaluator();

    private static FundPremiumResponse premium(String rate, String basis, Long staleDays) {
        return new FundPremiumResponse(LocalDate.parse("2026-10-26"), 4.18, 4.1792,
                java.time.OffsetDateTime.parse("2026-10-26T14:59:00+08:00"),
                4.1797, LocalDate.parse("2026-10-26"), staleDays,
                rate == null ? null : Double.valueOf(rate), basis,
                basis == null ? null : "2026-10-26",
                "tencent-qt", "eastmoney-f10-lsjz", null);
    }

    private static EvaluationContext context(FundPremiumResponse premium) {
        return EvaluationContext.ofQuote(null, premium);
    }

    @Test
    void aboveIncludesBoundary() {
        var params = om.createObjectNode().put("threshold", 2.5);
        assertThat(above.evaluate(params, context(premium("2.5", "IOPV", 0L)))).isTrue();
        assertThat(above.evaluate(params, context(premium("2.49", "IOPV", 0L)))).isFalse();
    }

    @Test
    void belowHandlesNegativeThreshold() {
        var params = om.createObjectNode().put("threshold", -1.5);
        assertThat(below.evaluate(params, context(premium("-1.5", "IOPV", 0L)))).isTrue();
        assertThat(below.evaluate(params, context(premium("-1.2", "IOPV", 0L)))).isFalse();
    }

    @Test
    void missingBasisNeverTriggers() {
        var params = om.createObjectNode().put("threshold", 1);
        assertThat(above.evaluate(params, context(premium(null, null, null)))).isFalse();
        assertThat(above.evaluate(params, context(null))).isFalse();
        assertThat(above.evaluate(params, EvaluationContext.ofQuote(null))).isFalse();
    }

    @Test
    void staleNavBasisIsSkippedByDefault() {
        var params = om.createObjectNode().put("threshold", 1);
        // LOF 用 T-1 净值算溢价属于正常，3 天内照常判定
        assertThat(above.evaluate(params, context(premium("3", "NAV", 1L)))).isTrue();
        // 净值 5 天没更新：这已经不是"实时溢价"，宁可少推一条
        assertThat(above.evaluate(params, context(premium("3", "NAV", 5L)))).isFalse();
        // 没有净值日期同样不能判定
        assertThat(above.evaluate(params, context(premium("3", "NAV", null)))).isFalse();
    }

    @Test
    void maxStaleDaysIsConfigurable() {
        var params = om.createObjectNode().put("threshold", 1).put("maxStaleDays", 7);
        assertThat(above.evaluate(params, context(premium("3", "NAV", 5L)))).isTrue();
        assertThat(above.evaluate(params, context(premium("3", "NAV", 8L)))).isFalse();
    }

    @Test
    void validateRejectsBadParams() {
        assertThatThrownBy(() -> above.validateParams(om.createObjectNode()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("threshold");
        assertThatThrownBy(() -> above.validateParams(om.createObjectNode().put("threshold", "2")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> above.validateParams(om.createObjectNode().put("threshold", 1).put("maxStaleDays", -1)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("maxStaleDays");
        above.validateParams(om.createObjectNode().put("threshold", 2.5));
    }

    @Test
    void premiumRulesDeclareTheirDependency() {
        assertThat(above.requiresPremium()).isTrue();
        assertThat(below.requiresPremium()).isTrue();
        assertThat(above.requiresHistory()).isFalse();
        assertThat(above.ruleType()).isEqualTo("PREMIUM_ABOVE");
    }
}
