package com.stockcat.alert;

import org.springframework.stereotype.Component;

/** 最新价低于阈值（含边界 <=） */
@Component
public class PriceBelowEvaluator extends AbstractPriceEvaluator {

    @Override
    public String ruleType() {
        return "PRICE_BELOW";
    }

    @Override
    protected boolean compare(double lastPrice, double threshold) {
        return lastPrice <= threshold;
    }
}
