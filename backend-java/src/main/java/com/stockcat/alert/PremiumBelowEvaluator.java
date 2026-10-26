package com.stockcat.alert;

import org.springframework.stereotype.Component;

/** 溢价率低于阈值（含边界 <=）。阈值可以是负数：−1.5 表示折价 1.5%。 */
@Component
public class PremiumBelowEvaluator extends AbstractPremiumEvaluator {

    @Override
    public String ruleType() {
        return "PREMIUM_BELOW";
    }

    @Override
    protected boolean compare(double premiumRate, double threshold) {
        return premiumRate <= threshold;
    }
}
