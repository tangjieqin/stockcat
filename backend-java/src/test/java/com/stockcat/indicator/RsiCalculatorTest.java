package com.stockcat.indicator;

import com.stockcat.domain.KlineDaily;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * RSI 口径验收：按通达信 SMA(X,N,1) 手算校验，不依赖外部数据。
 *
 * <p>为什么值得单独测：ta4j 的 RSIIndicator 用的是 α=2/(N+1) 的 EMA 递推，
 * 与国内看盘软件的 α=1/N 差很多（同一份真实数据 RSI6 差 13 个点）。
 * 这里用可以纸笔复算的小样例把公式钉死，避免以后有人"优化"成 EMA。
 */
class RsiCalculatorTest {

    @Test
    void matchesHandComputedSmaRecursion() {
        // 收盘 10,11,12,11,13 → 涨跌 +1,+1,-1,+2
        List<KlineDaily> bars = bars(10, 11, 12, 11, 13);
        RsiCalculator rsi = new RsiCalculator(3);

        List<Double> values = rsi.calculate(bars);

        // 前 3 根预热不足
        assertThat(values.subList(0, 3)).containsOnlyNulls();
        // i=3: up=(0+2*1)/3=0.6667, abs=(1+2*1)/3=1 → 66.6667
        assertThat(values.get(3)).isCloseTo(66.6667, within(1e-4));
        // i=4: up=(2+2*0.6667)/3=1.1111, abs=(2+2*1)/3=1.3333 → 83.3333
        assertThat(values.get(4)).isCloseTo(83.3333, within(1e-4));
    }

    @Test
    void twoPeriodExampleIsExactlyFifty() {
        // 10 → 11 → 10：涨 1 再跌 1，对称，RSI(2) 恰为 50
        List<Double> values = new RsiCalculator(2).calculate(bars(10, 11, 10));

        assertThat(values.get(0)).isNull();
        assertThat(values.get(1)).isNull();
        assertThat(values.get(2)).isCloseTo(50.0, within(1e-9));
    }

    @Test
    void monotonicRiseSaturatesAtHundred() {
        List<KlineDaily> bars = new ArrayList<>();
        double close = 10.0;
        for (int i = 0; i < 20; i++) {
            bars.add(bar(close));
            close += 0.5;
        }

        List<Double> values = new RsiCalculator(6).calculate(bars);

        assertThat(values.get(19)).isCloseTo(100.0, within(1e-9));
    }

    @Test
    void flatSeriesIsNullNotZero() {
        // 价格完全不动：分母 0，RSI 没有定义。返回 0 会被画成"跌到极值"，比空着更误导
        List<KlineDaily> bars = bars(10, 10, 10, 10, 10, 10, 10, 10);

        List<Double> values = new RsiCalculator(6).calculate(bars);

        assertThat(values.get(7)).isNull();
    }

    @Test
    void requiredBarsCountsOneMoreThanPeriod() {
        assertThat(new RsiCalculator(6).requiredBars()).isEqualTo(7);
        assertThat(new RsiCalculator(24).requiredBars()).isEqualTo(25);
    }

    @Test
    void missingCloseIsRejectedLoudly() {
        List<KlineDaily> bars = bars(10, 11, 12);
        bars.get(1).setClose(null);

        assertThatThrownBy(() -> new RsiCalculator(2).calculate(bars))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少收盘价");
    }

    @Test
    void emptyInputReturnsEmptyList() {
        assertThat(new RsiCalculator(6).calculate(List.of())).isEmpty();
    }

    private static List<KlineDaily> bars(double... closes) {
        List<KlineDaily> bars = new ArrayList<>();
        for (double close : closes) {
            bars.add(bar(close));
        }
        return bars;
    }

    private static KlineDaily bar(double close) {
        KlineDaily bar = new KlineDaily();
        bar.setOpen(close);
        bar.setHigh(close);
        bar.setLow(close);
        bar.setClose(close);
        bar.setVolume(1000.0);
        return bar;
    }
}
