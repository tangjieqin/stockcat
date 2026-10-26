package com.stockcat.indicator;

import com.stockcat.domain.KlineDaily;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技术指标计算（P1.3）：MACD(12,26,9)、RSI(6/12/24)、KDJ(9,3,3)。
 *
 * <p>指标<b>不落库</b>（决策 D2）：按查询现算，避免「K 线被修正 → 指标失效」的一致性负担，
 * 也避免 120 根 × N 只标的每天产生上万行冗余数据。
 *
 * <p><b>为什么三者都自研、不用 ta4j</b>（实测结论，不是偏好）：
 * <ul>
 *   <li>RSI：ta4j 用 α=2/(N+1) 的 EMA 平滑，通达信用 α=1/N 的 SMA 递推。同一份数据
 *       RSI(6) 差 13 个点（62.19 vs 75.47）——见 {@link RsiCalculator}</li>
 *   <li>KDJ：ta4j 的 D 是 K 的简单均值，国内是 SMA 递推；且 ta4j 没有 J = 3K − 2D</li>
 *   <li>MACD：ta4j 的 EMA 初值与通达信不同，120 根后 EMA26 仍偏 0.04 元，
 *       使 DIF 相对偏差约 0.3%</li>
 * </ul>
 * 这三条都属于"数值看着差不多、和看盘软件对不上"，而本项目的告警正是靠指标穿越触发，
 * 差一点就可能多推或少推一条通知。既然公式本身只有几行，自研比调教 ta4j 更可靠，
 * 也顺带消掉了「ta4j 0.22+ 需要 Java 25」的升级隐患。
 *
 * <p>口径（全部与通达信一致）：
 * <ul>
 *   <li>EMA(X,N)：{@code Y = (2X + (N-1)Y') / (N+1)}，首根 Y = X</li>
 *   <li>DIF = EMA(C,12) − EMA(C,26)，DEA = EMA(DIF,9)，<b>柱 = 2 × (DIF − DEA)</b></li>
 *   <li>RSI：SMA(MAX(C−LC,0),N,1) / SMA(ABS(C−LC),N,1) × 100</li>
 *   <li>KDJ：见 {@link KdjCalculator}</li>
 * </ul>
 *
 * <p>预热不足一律返回 null，不返回 0：0 会被前端画成一条贴底的线，看起来像"指标到极值了"，
 * 比空着更容易误判。
 */
@Component
public class IndicatorCalculator {

    public static final int MACD_FAST = 12;
    public static final int MACD_SLOW = 26;
    public static final int MACD_SIGNAL = 9;
    /** DIF 至少要 SLOW 根，DEA 再对 DIF 平滑 SIGNAL 根 → 下标 SLOW+SIGNAL-2 起才给值。 */
    public static final int MACD_MIN_INDEX = MACD_SLOW + MACD_SIGNAL - 2;

    public static final int RSI_SHORT = RsiCalculator.DEFAULT_SHORT;
    public static final int RSI_MID = RsiCalculator.DEFAULT_MID;
    public static final int RSI_LONG = RsiCalculator.DEFAULT_LONG;

    private final KdjCalculator kdj = KdjCalculator.standard();
    private final RsiCalculator rsiShort = RsiCalculator.standardShort();
    private final RsiCalculator rsiMid = RsiCalculator.standardMid();
    private final RsiCalculator rsiLong = RsiCalculator.standardLong();

    /**
     * @param bars 按日期升序、收盘价非空的日 K
     * @return 与 bars 等长，逐根对齐（同下标同日期）
     */
    public List<IndicatorValues> calculate(List<KlineDaily> bars) {
        List<IndicatorValues> result = new ArrayList<>(bars.size());
        if (bars.isEmpty()) {
            return result;
        }

        int n = bars.size();
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            Double close = bars.get(i).getClose();
            if (close == null) {
                throw new IllegalArgumentException(
                        "第 " + (i + 1) + " 根 K 线缺少收盘价，无法计算指标: " + bars.get(i).getTradeDate());
            }
            closes[i] = close;
        }

        double[] emaFast = ema(closes, MACD_FAST);
        double[] emaSlow = ema(closes, MACD_SLOW);
        double[] dif = new double[n];
        for (int i = 0; i < n; i++) {
            dif[i] = emaFast[i] - emaSlow[i];
        }
        double[] dea = ema(dif, MACD_SIGNAL);

        List<Double> rsi6 = rsiShort.calculate(bars);
        List<Double> rsi12 = rsiMid.calculate(bars);
        List<Double> rsi24 = rsiLong.calculate(bars);
        List<KdjCalculator.KdjPoint> kdjPoints = kdj.calculate(bars);

        for (int i = 0; i < n; i++) {
            boolean macdReady = i >= MACD_MIN_INDEX;
            Double d = macdReady ? dif[i] : null;
            Double e = macdReady ? dea[i] : null;
            Double hist = (d != null && e != null) ? 2 * (d - e) : null;

            KdjCalculator.KdjPoint point = kdjPoints.get(i);
            result.add(new IndicatorValues(
                    d, e, hist,
                    rsi6.get(i), rsi12.get(i), rsi24.get(i),
                    point == null ? null : point.k(),
                    point == null ? null : point.d(),
                    point == null ? null : point.j()));
        }
        return result;
    }

    /**
     * 每个指标需要的最少 K 线根数，供前端提示"数据不足"和判断指标可用性。
     */
    public Map<String, Integer> requiredBars() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("macd", MACD_MIN_INDEX + 1);
        map.put("rsi6", rsiShort.requiredBars());
        map.put("rsi12", rsiMid.requiredBars());
        map.put("rsi24", rsiLong.requiredBars());
        map.put("kdj", kdj.requiredBars());
        return map;
    }

    /**
     * 通达信 EMA：{@code Y = (2X + (N-1)Y') / (N+1)}，首根以 X 为初值。
     *
     * <p>首根初值不能省成 0 或 SMA 前 N 根：那样在 120 根窗口里 EMA26 会留 0.04 元的残差，
     * 正好落在"和看盘软件差一点点"的区间，排查起来很费时间。
     */
    static double[] ema(double[] values, int period) {
        double[] out = new double[values.length];
        if (values.length == 0) {
            return out;
        }
        double alpha = 2.0 / (period + 1);
        out[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1];
        }
        return out;
    }

    /** 单根 K 线的全部指标值；未预热的字段为 null。 */
    public record IndicatorValues(
            Double dif, Double dea, Double macdHist,
            Double rsi6, Double rsi12, Double rsi24,
            Double k, Double d, Double j
    ) {

        /** 受支持的 RSI 周期（与接口固定输出对应）。 */
        public static final java.util.List<Integer> RSI_PERIODS = java.util.List.of(RSI_SHORT, RSI_MID, RSI_LONG);

        /** 按周期取 RSI 值；未支持的周期返回 null（由调用方在参数校验阶段就拒掉）。 */
        public Double rsi(int period) {
            return switch (period) {
                case RSI_SHORT -> rsi6;
                case RSI_MID -> rsi12;
                case RSI_LONG -> rsi24;
                default -> null;
            };
        }
    }
}
