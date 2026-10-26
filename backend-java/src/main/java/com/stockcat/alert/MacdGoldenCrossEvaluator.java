package com.stockcat.alert;

import com.stockcat.indicator.IndicatorCalculator;
import com.stockcat.indicator.IndicatorCalculator.IndicatorValues;
import org.springframework.stereotype.Component;

/**
 * MACD 金叉：DIF 上穿 DEA（含零轴上下的所有位置）。
 *
 * <p>需要 35 根 K 线：DIF/DEA 从第 34 根（下标 33）起才有值，再要一根用来判断"上一根"。
 */
@Component
public class MacdGoldenCrossEvaluator extends AbstractCrossEvaluator {

    @Override
    public String ruleType() {
        return "MACD_GOLDEN_CROSS";
    }

    @Override
    public int requiredBars(com.fasterxml.jackson.databind.JsonNode params) {
        return IndicatorCalculator.MACD_MIN_INDEX + 2;
    }

    @Override
    protected double[] lines(IndicatorValues v) {
        if (v == null || v.dif() == null || v.dea() == null) {
            return null;
        }
        return new double[]{v.dif(), v.dea()};
    }

    @Override
    protected boolean isGolden() {
        return true;
    }
}
