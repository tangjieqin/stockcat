package com.stockcat.alert;

import com.stockcat.indicator.IndicatorCalculator.IndicatorValues;
import org.springframework.stereotype.Component;

/** KDJ 死叉：K 下穿 D。 */
@Component
public class KdjDeathCrossEvaluator extends AbstractCrossEvaluator {

    @Override
    public String ruleType() {
        return "KDJ_DEATH_CROSS";
    }

    @Override
    public int requiredBars(com.fasterxml.jackson.databind.JsonNode params) {
        return 10;
    }

    @Override
    protected double[] lines(IndicatorValues v) {
        if (v == null || v.k() == null || v.d() == null) {
            return null;
        }
        return new double[]{v.k(), v.d()};
    }

    @Override
    protected boolean isGolden() {
        return false;
    }
}
