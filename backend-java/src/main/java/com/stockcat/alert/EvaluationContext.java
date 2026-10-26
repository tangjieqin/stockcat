package com.stockcat.alert;

import com.stockcat.domain.KlineDaily;
import com.stockcat.indicator.IndicatorCalculator;
import com.stockcat.web.dto.FundPremiumResponse;
import com.stockcat.web.dto.QuoteResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 规则评估的输入上下文。
 *
 * <p>P0 的评估器只需要一条行情快照，P1 的指标类规则需要"一串已收盘日 K + 算好的指标"，
 * P1.5 的溢价规则还要基金参考数据（IOPV/净值/溢价率）。
 * 与其为三类规则写三套接口和三套调用路径，不如把"评估需要什么输入"收成一个上下文：
 * 行情类规则读 {@link #quote()}，指标类规则读 {@link #bars()} / {@link #indicators()}，
 * 溢价类规则读 {@link #premium()}。
 *
 * <p>刻意不做成 Map<String,Object> 之类的万能容器：那样编译器帮不上忙，
 * 字段名写错只会在运行时变成"规则永远不触发"，而告警不触发是最难发现的故障。
 */
public record EvaluationContext(
        QuoteResponse quote,
        List<KlineDaily> bars,
        List<IndicatorCalculator.IndicatorValues> indicators,
        FundPremiumResponse premium
) {

    public EvaluationContext {
        bars = bars == null ? List.of() : List.copyOf(bars);
        indicators = indicators == null ? List.of() : List.copyOf(indicators);
        if (bars.size() != indicators.size()) {
            throw new IllegalArgumentException(
                    "K 线与指标长度必须一致: bars=" + bars.size() + ", indicators=" + indicators.size());
        }
    }

    /** 盘中评估：只有行情快照 */
    public static EvaluationContext ofQuote(QuoteResponse quote) {
        return ofQuote(quote, null);
    }

    /** 盘中评估（基金）：行情快照 + 溢价视图；premium 为 null 表示取不到，溢价规则自然不触发 */
    public static EvaluationContext ofQuote(QuoteResponse quote, FundPremiumResponse premium) {
        return new EvaluationContext(quote, List.of(), List.of(), premium);
    }

    /**
     * 收盘后评估：按日期升序、只含已收盘 K 线（写入时已拒绝未收盘的当日 K）。
     */
    public static EvaluationContext ofHistory(List<KlineDaily> bars,
                                              List<IndicatorCalculator.IndicatorValues> indicators) {
        return new EvaluationContext(null, bars, indicators, null);
    }

    public boolean hasQuote() {
        return quote != null && quote.lastPrice() != null;
    }

    public boolean hasHistory() {
        return !indicators.isEmpty();
    }

    /** 有溢价视图且算出了溢价率（基准缺失时为 false，规则不触发） */
    public boolean hasPremium() {
        return premium != null && premium.premiumRate() != null;
    }

    public int size() {
        return indicators.size();
    }

    /** 最后一根（最新）K 线的指标值；无历史时返回 null。 */
    public IndicatorCalculator.IndicatorValues latest() {
        return hasHistory() ? indicators.get(indicators.size() - 1) : null;
    }

    /** 上一根 K 线的指标值；不足两根时返回 null。 */
    public IndicatorCalculator.IndicatorValues previous() {
        return indicators.size() >= 2 ? indicators.get(indicators.size() - 2) : null;
    }

    /** 最新 K 线的交易日，用作"一天只推一次"的去重维度；无历史时返回 null。 */
    public LocalDate latestTradeDate() {
        return bars.isEmpty() ? null : bars.get(bars.size() - 1).getTradeDate();
    }

    /** 最新价：盘中取行情快照，收盘后取最后一根 K 线的收盘价。 */
    public Double latestPrice() {
        if (hasQuote()) {
            return quote.lastPrice();
        }
        if (bars.isEmpty()) {
            return null;
        }
        return bars.get(bars.size() - 1).getClose();
    }
}
