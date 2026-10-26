package com.stockcat.history;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 历史行情客户端字段映射与错误翻译（不访问网络、不启动 Python）。
 * 响应样例取自 P1.1 真实调用（600519，2026-10-26）。
 */
class HistoryDataClientTest {

    private final HistoryDataClient client = new HistoryDataClient("http://127.0.0.1:8100", 15);

    private static final String SAMPLE = """
            {
              "source": "eastmoney-push2his",
              "secid": "1.600519",
              "symbol": "600519",
              "period": "daily",
              "adjust": "qfq",
              "requestedLimit": 3,
              "fetchedAt": "2026-10-26T11:16:58+00:00",
              "barCount": 3,
              "skippedBars": 1,
              "bars": [
                {"date": "2026-10-22", "open": 1310.0, "high": 1320.5, "low": 1305.0, "close": 1318.49,
                 "volume": 38000.0, "amount": 5000000000.0, "changePercent": 0.65, "turnoverRate": 0.30},
                {"date": "2026-10-23", "open": 1325.4, "high": 1350.0, "low": 1324.79, "close": 1349.98,
                 "volume": 41599.0, "amount": 5574287630.0, "changePercent": 1.12, "turnoverRate": 0.33},
                {"date": "2026-10-26", "open": 1350.0, "high": 1355.0, "low": 1340.0, "close": 1348.0,
                 "volume": null, "amount": null, "changePercent": null, "turnoverRate": null}
              ]
            }
            """;

    @Test
    void parse_mapsBarsAndMetadata() {
        KlineFetch fetch = client.parse(SAMPLE);

        assertThat(fetch.source()).isEqualTo("eastmoney-push2his");
        assertThat(fetch.secid()).isEqualTo("1.600519");
        assertThat(fetch.symbol()).isEqualTo("600519");
        assertThat(fetch.adjust()).isEqualTo("qfq");
        assertThat(fetch.barCount()).isEqualTo(3);
        assertThat(fetch.skippedBars()).isEqualTo(1);
        assertThat(fetch.fetchedAt()).isNotNull();
        assertThat(fetch.bars()).hasSize(3);

        DailyBar bar = fetch.bars().get(1);
        assertThat(bar.tradeDate()).hasToString("2026-10-23");
        assertThat(bar.open()).isEqualTo(1325.4);
        assertThat(bar.high()).isEqualTo(1350.0);
        assertThat(bar.low()).isEqualTo(1324.79);
        assertThat(bar.close()).isEqualTo(1349.98);
        assertThat(bar.volume()).isEqualTo(41599.0);
        assertThat(bar.changePercent()).isEqualTo(1.12);
    }

    @Test
    void parse_missingFieldsAreNullNotZero() {
        DailyBar bar = client.parse(SAMPLE).bars().get(2);

        // 停牌/未收盘等场景下上游会给 null，这里保持 null，交业务层判定，不补 0
        assertThat(bar.volume()).isNull();
        assertThat(bar.amount()).isNull();
        assertThat(bar.changePercent()).isNull();
        assertThat(bar.close()).isEqualTo(1348.0);
    }

    @Test
    void parse_invalidJsonThrowsInvalidResponse() {
        assertThatThrownBy(() -> client.parse("not-json"))
                .isInstanceOf(HistorySourceException.class)
                .satisfies(e -> assertThat(((HistorySourceException) e).getCode()).isEqualTo("INVALID_RESPONSE"));
    }

    @Test
    void parse_emptyBarsReturnsEmptyList() {
        KlineFetch fetch = client.parse("{\"source\":\"eastmoney-push2his\",\"bars\":[]}");
        assertThat(fetch.bars()).isEmpty();
        assertThat(fetch.barCount()).isZero();
    }

    @Test
    void mapError_prefersUpstreamCode() {
        String body = """
                {"timestamp":"2026-10-26T11:16:58Z","status":404,"code":"NO_DATA",
                 "message":"上游未返回日K数据（贝特瑞(已切换)）","details":null,"path":"/internal/v1/kline"}
                """;
        HistorySourceException e = client.mapError(404, body);

        assertThat(e.getCode()).isEqualTo("NO_DATA");
        assertThat(e.getMessage()).contains("贝特瑞");
    }

    @Test
    void mapError_fallsBackToHttpStatusWhenBodyIsNotJson() {
        assertThat(client.mapError(504, "<html>gateway timeout</html>").getCode()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(client.mapError(502, "").getCode()).isEqualTo("UPSTREAM_ERROR");
        assertThat(client.mapError(503, "").getCode()).isEqualTo("DATA_SOURCE_UNAVAILABLE");
    }

    @Test
    void fetch_whenAdapterNotRunningThrowsUnavailableWithActionableMessage() {
        // 只连本机一个必然拒绝的端口，验证「Python 没起」这一最常见失败不会被冒泡成 500
        HistoryDataClient downClient = new HistoryDataClient("http://127.0.0.1:1", 2);

        assertThatThrownBy(() -> downClient.fetch("1.600519", "qfq", 5, null))
                .isInstanceOf(HistorySourceException.class)
                .satisfies(e -> {
                    assertThat(((HistorySourceException) e).getCode()).isEqualTo("DATA_SOURCE_UNAVAILABLE");
                    assertThat(e.getMessage()).contains("backend-python");
                });
    }
}
