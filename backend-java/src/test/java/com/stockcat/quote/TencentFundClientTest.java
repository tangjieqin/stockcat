package com.stockcat.quote;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 腾讯基金行情解析（P1.4）。夹具是 2026-10-26 22:40 录制的**原始 GBK 字节**，
 * 不是手写的 JSON —— 这个源最容易错的地方就是编码与字段位。
 */
class TencentFundClientTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static String fixture() {
        try (InputStream in = TencentFundClientTest.class.getResourceAsStream("/fixtures/tencent_fund_quote_gbk.txt")) {
            if (in == null) {
                throw new IllegalStateException("找不到夹具 tencent_fund_quote_gbk.txt");
            }
            return new String(in.readAllBytes(), GBK);
        } catch (Exception e) {
            throw new IllegalStateException("读取夹具失败", e);
        }
    }

    private static final TencentFundClient CLIENT = new TencentFundClient("https://qt.gtimg.cn", 3);

    @Test
    void parsesEtfWithIopvAndLofWithoutIopv() {
        List<FundQuote> quotes = CLIENT.parse(fixture());

        assertEquals(3, quotes.size(), "响应里 510300 / 161725 / 600519 三只都应被解析");

        FundQuote etf = quotes.stream().filter(q -> "510300".equals(q.code())).findFirst().orElseThrow();
        assertEquals("沪深300ETF华泰柏瑞", etf.name(), "GBK 必须正确解码，否则名称是乱码");
        assertEquals("ETF", etf.type());
        assertEquals(4.179, etf.price(), 1e-9);
        assertEquals(4.1782, etf.iopv(), 1e-9);
        assertEquals(4.1797, etf.nav(), 1e-9);
        assertEquals(OffsetDateTime.parse("2026-10-26T16:14:32+08:00"), etf.marketTime());

        FundQuote lof = quotes.stream().filter(q -> "161725".equals(q.code())).findFirst().orElseThrow();
        assertEquals("LOF", lof.type());
        assertNull(lof.iopv(), "LOF 没有 IOPV，必须是 null 而不是 0");
        assertEquals(0.5459, lof.nav(), 1e-9);
    }

    @Test
    void stockRowHasNoFundFields() {
        FundQuote stock = CLIENT.parse(fixture()).stream()
                .filter(q -> "600519".equals(q.code())).findFirst().orElseThrow();

        assertEquals("GP-A", stock.type());
        assertNull(stock.iopv());
        assertNull(stock.nav());
        assertEquals(1349.98, stock.price(), 1e-9);
    }

    @Test
    void errorLineIsIgnoredNotThrown() {
        List<FundQuote> quotes = CLIENT.parse("v_pv_none_match=\"1\";\n");

        assertTrue(quotes.isEmpty());
    }

    @Test
    void missingTimeLeavesMarketTimeNull() {
        String body = "v_sh510300=\"" + "~".repeat(1) + "\";";
        assertTrue(CLIENT.parse(body).isEmpty(), "字段数不足的行直接跳过，不构造半条数据");
    }

    @Test
    void symbolToTencentCodeFollowsExchangeRule() {
        assertEquals("sh510300", TencentFundClient.toTencentCode("510300"));
        assertEquals("sh600519", TencentFundClient.toTencentCode("600519"));
        assertEquals("sz161725", TencentFundClient.toTencentCode("161725"));
        assertEquals("sz159915", TencentFundClient.toTencentCode("159915"));
        assertNull(TencentFundClient.toTencentCode("835185"), "北交所不在规则内，返回 null 而不是猜");
        assertNull(TencentFundClient.toTencentCode(null));
    }
}
