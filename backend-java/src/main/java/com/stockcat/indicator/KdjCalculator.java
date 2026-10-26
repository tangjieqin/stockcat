package com.stockcat.indicator;

import com.stockcat.domain.KlineDaily;

import java.util.ArrayList;
import java.util.List;

/**
 * KDJ(9,3,3) 计算器。
 *
 * <p>为什么不用 ta4j 的 StochasticOscillator：
 * ta4j 的 D 是「K 的 3 日简单移动平均」，而国内看盘软件（通达信/同花顺）的 K、D 是
 * <b>SMA 递推</b>：K = (2×K₋₁ + RSV)/3，D = (2×D₋₁ + K)/3。两者数值不同，直接采信 ta4j 会和用户看到的不一致。
 *
 * <p>口径说明（与通达信一致）：
 * <ul>
 *   <li>RSV = (收盘 − N 日最低) / (N 日最高 − N 日最低) × 100，N 含当日</li>
 *   <li>K = (2×K₋₁ + RSV)/3，D = (2×D₋₁ + K)/3，<b>首根把 K₋₁、D₋₁ 视为 50</b>
 *       （所以第一根的 K 已经吃进了当天的 RSV，而不是直接写 50）</li>
 *   <li>J = 3K − 2D，<b>不做 0~100 裁剪</b>——J 超出范围是正常现象，裁了反而和软件对不上</li>
 *   <li>区间为 0（一字板 / 全期同价）时沿用上一根 RSV，避免除零把 KDJ 打到 0</li>
 * </ul>
 */
public final class KdjCalculator {

    public static final int DEFAULT_N = 9;
    public static final int DEFAULT_M1 = 3;
    public static final int DEFAULT_M2 = 3;

    private final int n;
    private final double kAlpha;   // 1/M1
    private final double dAlpha;   // 1/M2
    private final double initial;  // K、D 初值

    public KdjCalculator(int n, int m1, int m2, double initial) {
        if (n < 1 || m1 < 1 || m2 < 1) {
            throw new IllegalArgumentException("KDJ 参数必须为正整数");
        }
        this.n = n;
        this.kAlpha = 1.0 / m1;
        this.dAlpha = 1.0 / m2;
        this.initial = initial;
    }

    public static KdjCalculator standard() {
        return new KdjCalculator(DEFAULT_N, DEFAULT_M1, DEFAULT_M2, 50.0);
    }

    /** 最少需要 n 根 K 线才能算出第一个 KDJ 值。 */
    public int requiredBars() {
        return n;
    }

    /**
     * @param bars 按日期升序
     * @return 与 bars 等长，前 n-1 项为 null（预热不足）
     */
    public List<KdjPoint> calculate(List<KlineDaily> bars) {
        List<KdjPoint> result = new ArrayList<>(bars.size());
        // K₋₁、D₋₁ 视为 50：第一根就吃进当天的 RSV，避免"第一天恒为 50、和 RSV 无关"的怪现象
        Double prevK = initial;
        Double prevD = initial;
        Double prevRsv = null;

        for (int i = 0; i < bars.size(); i++) {
            if (i < n - 1) {
                result.add(null);
                continue;
            }
            KlineDaily current = bars.get(i);
            double highest = Double.NEGATIVE_INFINITY;
            double lowest = Double.POSITIVE_INFINITY;
            boolean missing = false;
            for (int j = i - n + 1; j <= i; j++) {
                KlineDaily bar = bars.get(j);
                if (bar.getHigh() == null || bar.getLow() == null) {
                    missing = true;
                    break;
                }
                highest = Math.max(highest, bar.getHigh());
                lowest = Math.min(lowest, bar.getLow());
            }
            if (missing || current.getClose() == null) {
                result.add(null);
                continue;
            }

            double rsv;
            if (highest > lowest) {
                rsv = (current.getClose() - lowest) / (highest - lowest) * 100.0;
            } else {
                // 一字板等极端情况：区间为 0，沿用上一根的 RSV；首根没有上一根时取中性值
                rsv = prevRsv != null ? prevRsv : initial;
            }
            double k = prevK + kAlpha * (rsv - prevK);
            double d = prevD + dAlpha * (k - prevD);
            double j = 3 * k - 2 * d;

            result.add(new KdjPoint(k, d, j));
            prevK = k;
            prevD = d;
            prevRsv = rsv;
        }
        return result;
    }

    public record KdjPoint(double k, double d, double j) {
    }
}
