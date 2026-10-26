package com.stockcat.alert;

import org.springframework.stereotype.Component;

/**
 * RSI 下穿阈值（跌出超买 / 进入超卖），参数 {@code {"period": 6, "threshold": 20}}。
 */
@Component
public class RsiCrossBelowEvaluator extends AbstractRsiThresholdEvaluator {

    @Override
    public String ruleType() {
        return "RSI_CROSS_BELOW";
    }

    @Override
    protected boolean isAbove() {
        return false;
    }
}
