package com.stockcat.fund;

import java.time.LocalDate;

/**
 * 一期基金净值（来自天天基金 f10/lsjz，经 Python 适配器标准化）。
 *
 * @param date   净值日期（上游 FSRQ），这是判断"净值滞后几天"的唯一依据
 * @param nav    单位净值
 * @param accNav 累计净值
 * @param changePercent 日增长率（百分数值）
 */
public record FundNavPoint(LocalDate date, Double nav, Double accNav, Double changePercent) {
}
