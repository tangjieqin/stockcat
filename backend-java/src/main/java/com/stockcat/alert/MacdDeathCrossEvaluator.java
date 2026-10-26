package com.stockcat.alert;

import com.stockcat.indicator.IndicatorCalculator;
import com.stockcat.indicator.IndicatorCalculator.IndicatorValues;
import org.springframework.stereotype.Component;

/** MACD 死叉：DIF 下穿 DEA。 */
@Component
public class MacdDeathCrossEvaluator extends AbstractCrossEvaluator {

    @Override
    public String ruleType() {
        return "MACD_DEATH_CROSS";
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
        return false;
    }
}
