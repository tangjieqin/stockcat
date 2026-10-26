package com.stockcat.indicator;

import com.stockcat.domain.KlineDaily;

import java.util.ArrayList;
import java.util.List;

/**
 * RSI(N) 计算器，口径与通达信一致。
 *
 * <p><b>为什么不用 ta4j 的 RSIIndicator</b>：实测同一份数据（600519 前复权 120 根）
 * RSI(6) 在 2026-10-26 这天，ta4j 给 62.19、通达信给 75.47，<b>差 13 个点</b>。
 * 原因是平滑系数不同：ta4j 用 α=2/(N+1) 的 EMA 递推，通达信用 SMA(X,N,1)，即 α=1/N。
 * 指标显示给用户看，必须和用户盯的软件是同一个数，否则"金叉了但软件没金叉"会让整个功能失信。
 *
 * <p>通达信公式：
 * <pre>
 *   LC := REF(CLOSE, 1);
 *   RSI := SMA(MAX(CLOSE-LC, 0), N, 1) / SMA(ABS(CLOSE-LC), N, 1) * 100;
 *   SMA(X, N, M) := (M*X + (N-M)*Y') / N     // Y' 为上一根的值
 * </pre>
 * 即 {@code avg = (x + (N-1)*avg') / N}，首根以第一个变化值作为初值
 * （等价于 pandas {@code ewm(alpha=1/N, adjust=False)}，与国内主流复现一致）。
 *
 * <p>两个必须显式处理的边界：
 * <ul>
 *   <li>预热不足（i &lt; N）返回 null，不返回 0 —— 0 会被画成"跌到极值"</li>
 *   <li>分母为 0（价格连续不变）返回 null —— 0/0 不是 0，是"无定义"</li>
 * </ul>
 */
public final class RsiCalculator {

    public static final int DEFAULT_SHORT = 6;
    public static final int DEFAULT_MID = 12;
    public static final int DEFAULT_LONG = 24;

    private final int period;

    public RsiCalculator(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("RSI 周期必须为正整数: " + period);
        }
        this.period = period;
    }

    public static RsiCalculator standardShort() {
        return new RsiCalculator(DEFAULT_SHORT);
    }

    public static RsiCalculator standardMid() {
        return new RsiCalculator(DEFAULT_MID);
    }

    public static RsiCalculator standardLong() {
        return new RsiCalculator(DEFAULT_LONG);
    }

    public int period() {
        return period;
    }

    /** 需要 period + 1 根 K 线才能算出第一个值（period 个涨跌幅需要 period+1 个收盘价）。 */
    public int requiredBars() {
        return period + 1;
    }

    /**
     * @param bars 按日期升序、收盘价非空的日 K
     * @return 与 bars 等长，前 period 项为 null
     */
    public List<Double> calculate(List<KlineDaily> bars) {
        List<Double> result = new ArrayList<>(bars.size());
        Double upAvg = null;
        Double absAvg = null;
        Double prevClose = null;

        for (int i = 0; i < bars.size(); i++) {
            Double close = bars.get(i).getClose();
            if (close == null) {
                throw new IllegalArgumentException(
                        "第 " + (i + 1) + " 根 K 线缺少收盘价，无法计算 RSI: " + bars.get(i).getTradeDate());
            }
            if (prevClose == null) {
                result.add(null);
                prevClose = close;
                continue;
            }

            double change = close - prevClose;
            double gain = Math.max(change, 0.0);
            double abs = Math.abs(change);
            // SMA(X, N, 1) 递推：首根以自身为初值，之后 (x + (N-1)*prev) / N
            upAvg = upAvg == null ? gain : (gain + (period - 1) * upAvg) / period;
            absAvg = absAvg == null ? abs : (abs + (period - 1) * absAvg) / period;
            prevClose = close;

            if (i < period) {
                // 预热段：递推已经在跑（为后续准备），但对外不给值
                result.add(null);
            } else if (absAvg <= 0) {
                result.add(null);
            } else {
                result.add(100.0 * upAvg / absAvg);
            }
        }
        return result;
    }
}
