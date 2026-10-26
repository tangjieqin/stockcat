package com.stockcat.alert;

import org.springframework.stereotype.Component;

/** 涨跌幅低于阈值（含边界 <=），如 -20 表示跌破 20% */
@Component
public class ChangePercentBelowEvaluator extends AbstractChangePercentEvaluator {

    @Override
    public String ruleType() {
        return "CHANGE_PERCENT_BELOW";
    }

    @Override
    protected boolean compare(double changePercent, double threshold) {
        return changePercent <= threshold;
    }
}
