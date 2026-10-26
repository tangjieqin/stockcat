package com.stockcat.fund;

import com.stockcat.quote.FundSourceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java → Python 净值接口的解析与错误翻译（P1.4）。
 * 报文结构与 Python 侧 FundNavResponse 一致（camelCase）。
 */
class FundNavClientTest {

    private final FundNavClient client = new FundNavClient("http://127.0.0.1:8100", 3);

    @Test
    void parsesItemsAndMetadata() {
        String body = """
                {"source":"eastmoney-f10-lsjz","fundCode":"510300","requestedLimit":2,
                 "fetchedAt":"2026-10-26T14:40:00Z","totalCount":3517,"count":2,"skippedRows":1,
                 "items":[{"date":"2026-10-26","nav":4.1797,"accNav":1.8769,"changePercent":-0.03},
                          {"date":"2026-10-23","nav":4.1811,"accNav":1.8774,"changePercent":1.75}]}
                """;

        FundNavFetch fetch = client.parse(body);

        assertEquals("510300", fetch.fundCode());
        assertEquals(2, fetch.requestedLimit());
        assertEquals(3517, fetch.totalCount());
        assertEquals(1, fetch.skippedRows());
        assertEquals(2, fetch.items().size());
        FundNavPoint latest = fetch.items().get(0);
        assertEquals(LocalDate.parse("2026-10-26"), latest.date());
        assertEquals(4.1797, latest.nav(), 1e-9);
        assertEquals(1.8769, latest.accNav(), 1e-9);
        assertEquals(-0.03, latest.changePercent(), 1e-9);
    }

    @Test
    void rowWithoutNavIsDroppedNotZeroFilled() {
        String body = """
                {"fundCode":"510300","requestedLimit":2,"items":[
                  {"date":"2026-10-26","nav":null},
                  {"date":"2026-10-23","nav":4.1811}]}
                """;

        FundNavFetch fetch = client.parse(body);

        assertEquals(1, fetch.items().size());
        assertEquals(LocalDate.parse("2026-10-23"), fetch.items().get(0).date());
    }

    @Test
    void errorBodyCodeIsPreferredOverHttpStatus() {
        FundSourceException ex = client.mapError(404,
                "{\"status\":404,\"code\":\"NO_DATA\",\"message\":\"上游未返回净值数据\"}");

        assertEquals("NO_DATA", ex.getCode());
        assertTrue(ex.getMessage().contains("上游未返回净值数据"));
    }

    @Test
    void nonJsonBodyFallsBackToStatusCode() {
        assertEquals("UPSTREAM_TIMEOUT", client.mapError(504, "<html>gateway timeout</html>").getCode());
        assertEquals("DATA_SOURCE_UNAVAILABLE", client.mapError(500, "").getCode());
    }
}
