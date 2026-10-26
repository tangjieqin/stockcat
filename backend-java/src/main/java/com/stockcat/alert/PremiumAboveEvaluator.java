package com.stockcat.alert;

import org.springframework.stereotype.Component;

/** 溢价率高于阈值（含边界 >=）。阈值是百分数：2.5 表示溢价 2.5%。 */
@Component
public class PremiumAboveEvaluator extends AbstractPremiumEvaluator {

    @Override
    public String ruleType() {
        return "PREMIUM_ABOVE";
    }

    @Override
    protected boolean compare(double premiumRate, double threshold) {
        return premiumRate >= threshold;
    }
}
