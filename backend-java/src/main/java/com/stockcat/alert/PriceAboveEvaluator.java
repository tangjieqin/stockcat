package com.stockcat.alert;

import org.springframework.stereotype.Component;

/** 最新价高于阈值（含边界 >=） */
@Component
public class PriceAboveEvaluator extends AbstractPriceEvaluator {

    @Override
    public String ruleType() {
        return "PRICE_ABOVE";
    }

    @Override
    protected boolean compare(double lastPrice, double threshold) {
        return lastPrice >= threshold;
    }
}
