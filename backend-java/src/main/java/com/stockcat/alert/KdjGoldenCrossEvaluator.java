package com.stockcat.alert;

import com.stockcat.indicator.IndicatorCalculator.IndicatorValues;
import org.springframework.stereotype.Component;

/**
 * KDJ 金叉：K 上穿 D。
 *
 * <p>需要 10 根 K 线：KDJ 从第 9 根（下标 8）起才有值，再要一根判断"上一根"。
 */
@Component
public class KdjGoldenCrossEvaluator extends AbstractCrossEvaluator {

    @Override
    public String ruleType() {
        return "KDJ_GOLDEN_CROSS";
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
        return true;
    }
}
