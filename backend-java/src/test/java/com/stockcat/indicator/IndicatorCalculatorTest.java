package com.stockcat.indicator;

import com.stockcat.domain.KlineDaily;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 指标计算验收（P1.3）。
 *
 * <p>基准值不是「跑一遍代码把结果抄下来」，而是用一份**独立实现**（Python，按通达信公式
 * 逐行重写，代码与 Java 无共享）对同一份真实行情算出来的：600519 前复权 120 根，
 * 2026-04-28 ~ 2026-10-26。
 *
 * <p>两边公式一致，所以容差按「基准值的小数位数」给（基准值打印到 6/4 位），
 * 不再是"口径不同所以放宽"——容差一放宽，口径悄悄改回去也测不出来。
 */
class IndicatorCalculatorTest {

    private final IndicatorCalculator calculator = new IndicatorCalculator();

    @Test
    void matchesIndependentReferenceOnRealData() {
        List<KlineDaily> bars = loadFixture();
        List<IndicatorCalculator.IndicatorValues> values = calculator.calculate(bars);

        assertThat(values).hasSize(120);

        // 2026-10-22 / 10-23 / 10-26 三根，与 Python 独立实现逐项比对
        assertPoint(values.get(117), 10.714550, 3.269783, 14.889535, 62.1857, 60.2086, 55.4817,
                72.7362, 77.3884, 63.4318);
        assertPoint(values.get(118), 12.612872, 5.138401, 14.948942, 69.9709, 64.6336, 58.0099,
                76.3359, 77.0376, 74.9324);
        assertPoint(values.get(119), 15.151410, 7.141003, 16.020815, 75.4697, 68.1405, 60.1525,
                84.2160, 79.4304, 93.7871);
    }

    @Test
    void warmupValuesAreNullNotZero() {
        List<IndicatorCalculator.IndicatorValues> values = calculator.calculate(loadFixture());

        // MACD 第 34 根（下标 33）才有稳定值
        assertThat(values.get(32).dif()).isNull();
        assertThat(values.get(32).dea()).isNull();
        assertThat(values.get(32).macdHist()).isNull();
        assertThat(values.get(33).dif()).isNotNull();
        assertThat(values.get(33).dea()).isNotNull();

        // RSI(6) 需要 7 根，RSI(24) 需要 25 根
        assertThat(values.get(5).rsi6()).isNull();
        assertThat(values.get(6).rsi6()).isNotNull();
        assertThat(values.get(23).rsi24()).isNull();
        assertThat(values.get(24).rsi24()).isNotNull();

        // KDJ 需要 9 根
        assertThat(values.get(7).k()).isNull();
        assertThat(values.get(8).k()).isNotNull();
    }

    @Test
    void macdHistogramUsesChineseDoublingConvention() {
        IndicatorCalculator.IndicatorValues v = calculator.calculate(loadFixture()).get(119);

        // 通达信的 MACD 柱 = 2 × (DIF − DEA)，不是 (DIF − DEA)
        assertThat(v.macdHist()).isCloseTo(2 * (v.dif() - v.dea()), within(1e-9));
    }

    @Test
    void shortSeriesYieldsAllNullButDoesNotThrow() {
        List<KlineDaily> bars = loadFixture().subList(0, 10);
        List<IndicatorCalculator.IndicatorValues> values = calculator.calculate(bars);

        assertThat(values).hasSize(10);
        assertThat(values).allSatisfy(v -> {
            assertThat(v.dif()).isNull();
            assertThat(v.dea()).isNull();
            assertThat(v.macdHist()).isNull();
            assertThat(v.rsi24()).isNull();
        });
        // 10 根时 KDJ 与 RSI(6) 已经可用
        assertThat(values.get(9).k()).isNotNull();
        assertThat(values.get(9).rsi6()).isNotNull();
    }

    @Test
    void emptyInputReturnsEmptyResult() {
        assertThat(calculator.calculate(List.of())).isEmpty();
    }

    @Test
    void constantPriceSeriesHasZeroMacd() {
        List<KlineDaily> bars = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            KlineDaily bar = new KlineDaily();
            bar.setOpen(10.0);
            bar.setHigh(10.0);
            bar.setLow(10.0);
            bar.setClose(10.0);
            bar.setVolume(1000.0);
            bars.add(bar);
        }

        IndicatorCalculator.IndicatorValues last = calculator.calculate(bars).get(59);

        assertThat(last.dif()).isCloseTo(0.0, within(1e-9));
        assertThat(last.dea()).isCloseTo(0.0, within(1e-9));
        assertThat(last.macdHist()).isCloseTo(0.0, within(1e-9));
        // 价格完全不动时 RSI 无定义：返回 null，不能返回 0 或 NaN
        assertThat(last.rsi6()).isNull();
    }

    @Test
    void requiredBarsTellsFrontendWhenDataIsInsufficient() {
        assertThat(calculator.requiredBars())
                .containsEntry("macd", 34)
                .containsEntry("rsi6", 7)
                .containsEntry("rsi12", 13)
                .containsEntry("rsi24", 25)
                .containsEntry("kdj", 9);
    }

    private static void assertPoint(IndicatorCalculator.IndicatorValues v,
                                    double dif, double dea, double hist,
                                    double rsi6, double rsi12, double rsi24,
                                    double k, double d, double j) {
        assertThat(v.dif()).isCloseTo(dif, within(1e-6));
        assertThat(v.dea()).isCloseTo(dea, within(1e-6));
        assertThat(v.macdHist()).isCloseTo(hist, within(1e-6));
        assertThat(v.rsi6()).isCloseTo(rsi6, within(1e-4));
        assertThat(v.rsi12()).isCloseTo(rsi12, within(1e-4));
        assertThat(v.rsi24()).isCloseTo(rsi24, within(1e-4));
        assertThat(v.k()).isCloseTo(k, within(1e-4));
        assertThat(v.d()).isCloseTo(d, within(1e-4));
        assertThat(v.j()).isCloseTo(j, within(1e-4));
    }

    /** 真实行情夹具：600519 前复权 120 根（见文件头注释的来源）。 */
    private static List<KlineDaily> loadFixture() {
        String path = "/fixtures/kline_600519_qfq_120.csv";
        List<KlineDaily> bars = new ArrayList<>();
        try (InputStream in = IndicatorCalculatorTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("找不到测试夹具: " + path);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line = reader.readLine(); // header
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(",");
                KlineDaily bar = new KlineDaily();
                bar.setTradeDate(LocalDate.parse(f[0]));
                bar.setOpen(Double.parseDouble(f[1]));
                bar.setHigh(Double.parseDouble(f[2]));
                bar.setLow(Double.parseDouble(f[3]));
                bar.setClose(Double.parseDouble(f[4]));
                bar.setVolume(Double.parseDouble(f[5]));
                bar.setAmount(Double.parseDouble(f[6]));
                bars.add(bar);
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取测试夹具失败: " + path, e);
        }
        return bars;
    }
}
