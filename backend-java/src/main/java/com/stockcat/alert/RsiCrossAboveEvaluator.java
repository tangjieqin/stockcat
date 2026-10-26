package com.stockcat.alert;

import org.springframework.stereotype.Component;

/**
 * RSI 上穿阈值（进入超买），参数 {@code {"period": 6, "threshold": 80}}。
 * 例如"RSI6 上穿 80 提醒减仓"：只在上穿那天触发一次，之后即使一直超买也不再推。
 */
@Component
public class RsiCrossAboveEvaluator extends AbstractRsiThresholdEvaluator {

    @Override
    public String ruleType() {
        return "RSI_CROSS_ABOVE";
    }

    @Override
    protected boolean isAbove() {
        return true;
    }
}
