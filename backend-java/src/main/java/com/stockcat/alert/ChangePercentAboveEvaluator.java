package com.stockcat.alert;

import org.springframework.stereotype.Component;

/** 涨跌幅高于阈值（含边界 >=），如 +5 */
@Component
public class ChangePercentAboveEvaluator extends AbstractChangePercentEvaluator {

    @Override
    public String ruleType() {
        return "CHANGE_PERCENT_ABOVE";
    }

    @Override
    protected boolean compare(double changePercent, double threshold) {
        return changePercent >= threshold;
    }
}
