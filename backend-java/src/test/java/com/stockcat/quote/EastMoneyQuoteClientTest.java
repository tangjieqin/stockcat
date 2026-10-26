package com.stockcat.quote;

import com.stockcat.quote.EastMoneyQuoteClient.QuoteSourceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 行情源字段映射单元测试（不访问网络）。
 * 覆盖 docs/DEVELOPMENT_PLAN.md P0.3 验收：字段映射、缺失字段、异常场景。
 */
class EastMoneyQuoteClientTest {

    private final EastMoneyQuoteClient client = new EastMoneyQuoteClient("https://push2.eastmoney.com", 5);

    private static final String SAMPLE = """
            {"rc":0,"rt":11,"svr":177617626,"lt":1,"full":1,"dlmkts":"","dsc":"0","data":{"total":2,
            "diff":[
              {"f2":1335.0,"f3":1.25,"f5":57686,"f6":7677216283.0,"f12":"600519","f13":1,"f14":"贵州茅台","f18":1318.49,"f124":1792743118},
              {"f2":"-","f3":"-","f5":"-","f6":"-","f12":"000001","f13":0,"f14":"平安银行","f18":11.3,"f124":null}
            ]}}
            """;

    @Test
    void parse_normalFieldsMappedCorrectly() {
        List<StandardQuote> quotes = client.parse(SAMPLE);

        assertThat(quotes).hasSize(2);

        StandardQuote first = quotes.get(0);
        assertThat(first.symbol()).isEqualTo("600519");
        assertThat(first.name()).isEqualTo("贵州茅台");
        assertThat(first.lastPrice()).isEqualTo(1335.0);
        assertThat(first.previousClose()).isEqualTo(1318.49);
        assertThat(first.changePercent()).isEqualTo(1.25);
        assertThat(first.volume()).isEqualTo(57686.0);
        assertThat(first.turnover()).isEqualTo(7677216283.0);
        assertThat(first.marketTime()).isNotNull();
        assertThat(first.sourceCode()).isEqualTo("eastmoney-push2");
    }

    @Test
    void parse_missingOrDashFieldsAreNullNotZero() {
        List<StandardQuote> quotes = client.parse(SAMPLE);
        StandardQuote suspended = quotes.get(1);

        // 停牌标的: "-" 字段一律为 null，绝不补 0
        assertThat(suspended.lastPrice()).isNull();
        assertThat(suspended.changePercent()).isNull();
        assertThat(suspended.volume()).isNull();
        assertThat(suspended.turnover()).isNull();
        assertThat(suspended.marketTime()).isNull();
        // 昨收仍有效
        assertThat(suspended.previousClose()).isEqualTo(11.3);
    }

    @Test
    void parse_invalidJsonThrowsQuoteSourceException() {
        assertThatThrownBy(() -> client.parse("not-json"))
                .isInstanceOf(QuoteSourceException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    void parse_emptyDiffReturnsEmptyList() {
        String empty = "{\"data\":{\"total\":0,\"diff\":[]}}";
        assertThat(client.parse(empty)).isEmpty();
    }

    @Test
    void symbolMapping_shAndSzRules() {
        assertThat(SymbolMapping.cnAToSecid("600519")).isEqualTo("1.600519");
        assertThat(SymbolMapping.cnAToSecid("510300")).isEqualTo("1.510300");
        assertThat(SymbolMapping.cnAToSecid("000001")).isEqualTo("0.000001");
        assertThat(SymbolMapping.cnAToSecid("300750")).isEqualTo("0.300750");
        assertThat(SymbolMapping.cnAToSecid("159915")).isEqualTo("0.159915");
        // 北交所与未知前缀不支持，返回 null（不猜测）
        assertThat(SymbolMapping.cnAToSecid("832000")).isNull();
        assertThat(SymbolMapping.cnAToSecid("430047")).isNull();
        assertThat(SymbolMapping.cnAToSecid(null)).isNull();
    }
}
