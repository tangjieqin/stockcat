package com.stockcat.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockcat.common.ApiException;
import com.stockcat.web.dto.FundPremiumResponse;

/**
 * 溢价类规则公共实现：溢价率 ≥ / ≤ 阈值（含边界）。
 *
 * <p>溢价率从 {@link FundPremiumResponse#premiumRate()} 取，基准有两条（docs/DATA_SOURCES.md §3.1）：
 * <ul>
 *   <li>{@code IOPV}：ETF 盘中的实时净值，可以直接用；</li>
 *   <li>{@code NAV}：最近一期已公布净值，本身就滞后几天。用它算"实时溢价"其实是
 *       "价格相对 T-n 净值的偏离"——净值太旧时这个数已经没有折溢价的含义，所以默认
 *       超过 {@value #DEFAULT_MAX_STALE_DAYS} 天就不触发（可用 {@code maxStaleDays} 覆盖）。</li>
 * </ul>
 * 宁可少推一条，也不拿 5 天前的净值当实时溢价去提醒用户。
 */
abstract class AbstractPremiumEvaluator implements RuleEvaluator {

    static final int DEFAULT_MAX_STALE_DAYS = 3;

    @Override
    public void validateParams(JsonNode params) {
        if (params == null) {
            throw ApiException.validationError(ruleType() + " 需要参数 {\"threshold\": number}");
        }
        JsonNode threshold = params.get("threshold");
        if (threshold == null || !threshold.isNumber()) {
            throw ApiException.validationError(
                    ruleType() + " 需要数值参数 {\"threshold\": number}（百分数，2.5 表示溢价 2.5%）");
        }
        JsonNode maxStaleDays = params.get("maxStaleDays");
        if (maxStaleDays != null && (!maxStaleDays.isNumber() || maxStaleDays.asInt() < 0)) {
            throw ApiException.validationError("maxStaleDays 必须是非负整数");
        }
    }

    @Override
    public boolean requiresPremium() {
        return true;
    }

    protected abstract boolean compare(double premiumRate, double threshold);

    @Override
    public boolean evaluate(JsonNode params, EvaluationContext context) {
        if (context == null || !context.hasPremium()) {
            return false; // 没有基准（或基准缺价）就不算溢价，不猜
        }
        FundPremiumResponse premium = context.premium();
        if ("NAV".equals(premium.premiumBasis())) {
            int maxStaleDays = params.hasNonNull("maxStaleDays")
                    ? params.get("maxStaleDays").asInt()
                    : DEFAULT_MAX_STALE_DAYS;
            Long staleDays = premium.navStaleDays();
            if (staleDays == null || staleDays > maxStaleDays) {
                return false;
            }
        }
        return compare(premium.premiumRate(), params.get("threshold").asDouble());
    }
}
