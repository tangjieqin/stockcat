package com.stockcat.indicator;

import com.stockcat.domain.KlineDaily;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * KDJ 使用手算样例校验（不依赖 ta4j，也不依赖外部数据）。
 *
 * <p>样例：10 根 K 线，收盘 10 → 14.5 每天 +0.5，高低 = 收盘 ±1。
 * 第 9 根（下标 8）窗口覆盖 10~14：最高 15、最低 9，
 * RSV = (14−9)/(15−9)×100 = 83.3333
 * K = 50 + (83.3333−50)/3 = 61.1111
 * D = 50 + (61.1111−50)/3 = 53.7037
 * J = 3K − 2D = 75.9259
 */
class KdjCalculatorTest {

    private final KdjCalculator kdj = KdjCalculator.standard();

    @Test
    void calculatesKdjFromHandComputedExample() {
        List<KlineDaily> bars = rampBars(10);

        List<KdjCalculator.KdjPoint> points = kdj.calculate(bars);

        // 前 8 根预热不足
        assertThat(points.subList(0, 8)).containsOnlyNulls();

        KdjCalculator.KdjPoint p8 = points.get(8);
        assertThat(p8.k()).isCloseTo(61.1111, within(1e-3));
        assertThat(p8.d()).isCloseTo(53.7037, within(1e-3));
        assertThat(p8.j()).isCloseTo(75.9259, within(1e-3));

        KdjCalculator.KdjPoint p9 = points.get(9);
        assertThat(p9.k()).isCloseTo(68.5185, within(1e-3));
        assertThat(p9.d()).isCloseTo(58.6420, within(1e-3));
        assertThat(p9.j()).isCloseTo(88.2716, within(1e-3));
    }

    @Test
    void flatSeriesFallsBackToNeutralInsteadOfDividingByZero() {
        // 一字板：high == low，RSV 分母为 0。此时沿用中性值，KDJ 保持 50，不会被打到 0 或 NaN
        List<KlineDaily> bars = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            bars.add(bar(10.0, 10.0, 10.0, 10.0));
        }

        List<KdjCalculator.KdjPoint> points = kdj.calculate(bars);

        assertThat(points.get(11).k()).isEqualTo(50.0);
        assertThat(points.get(11).d()).isEqualTo(50.0);
        assertThat(points.get(11).j()).isEqualTo(50.0);
    }

    @Test
    void jIsNotClampedTo0To100() {
        // 单边匀速上涨时 RSV 恒定 → K、D 都收敛到同一个数，J 反而不会破 100（= RSV）。
        // 要让 J 破 100，需要 K 快速上冲而 D 滞后：先横盘 9 根，再跳空并连续涨停。
        // 横盘 9 根：high == low → RSV 取中性 50 → K = D = 50；
        // 第 1 根跳涨：K = (2×50 + 100)/3 = 66.67，D = (2×50 + 66.67)/3 = 55.56，J = 88.89
        // 第 2 根：K = 77.78，D = 62.96，J = 107.41 > 100 ← 必须保留这个值，不能裁剪
        List<KlineDaily> bars = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            bars.add(bar(10.0, 10.0, 10.0, 10.0));
        }
        for (int i = 0; i < 3; i++) {
            bars.add(bar(20.0, 20.0, 20.0, 20.0));
        }

        List<KdjCalculator.KdjPoint> points = kdj.calculate(bars);

        assertThat(points.get(10).j()).isGreaterThan(100.0);
        assertThat(points.get(10).k()).isLessThanOrEqualTo(100.0);
    }

    private static List<KlineDaily> rampBars(int count) {
        List<KlineDaily> bars = new ArrayList<>();
        double close = 10.0;
        for (int i = 0; i < count; i++) {
            bars.add(bar(close, close + 1, close - 1, close));
            close += 0.5;
        }
        return bars;
    }

    private static KlineDaily bar(double open, double high, double low, double close) {
        KlineDaily bar = new KlineDaily();
        bar.setOpen(open);
        bar.setHigh(high);
        bar.setLow(low);
        bar.setClose(close);
        bar.setVolume(1000.0);
        return bar;
    }
}
