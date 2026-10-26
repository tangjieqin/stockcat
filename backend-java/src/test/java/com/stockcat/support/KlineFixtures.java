package com.stockcat.support;

import com.stockcat.domain.KlineDaily;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 真实行情测试夹具：600519 前复权 120 根（2026-04-28 ~ 2026-10-26），
 * 由 `kline_daily` 实际落库数据导出，不是手造的假数据。
 *
 * <p>用它是因为指标和交叉判定必须能对上真实价格的抖动；
 * 纯手工造数容易造出"太干净"的曲线，掩盖预热、跳空、平盘等边界。
 */
public final class KlineFixtures {

    private static final String PATH_600519 = "/fixtures/kline_600519_qfq_120.csv";

    private KlineFixtures() {
    }

    public static List<KlineDaily> load600519() {
        List<KlineDaily> bars = new ArrayList<>();
        try (InputStream in = KlineFixtures.class.getResourceAsStream(PATH_600519)) {
            if (in == null) {
                throw new IllegalStateException("找不到测试夹具: " + PATH_600519);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            reader.readLine();   // header
            String line;
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
            throw new IllegalStateException("读取测试夹具失败: " + PATH_600519, e);
        }
        return bars;
    }

    /** 取前 count 根（用于构造"历史不足"与"最后一根正好是交叉点"的场景）。 */
    public static List<KlineDaily> head(int count) {
        List<KlineDaily> bars = load600519();
        return new ArrayList<>(bars.subList(0, Math.min(count, bars.size())));
    }
}
