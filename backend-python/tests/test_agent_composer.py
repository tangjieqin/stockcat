"""规则汇总：数字照实说，缺数据就写缺，不能补 0。"""
from app.agent.composer import compose

POSITIONS_BODY = {
    "tool": "positions",
    "dataAsOf": "2026-10-27T06:30:00Z",
    "data": {
        "count": 1,
        "totalMarketValue": 2511.6,
        "totalUnrealizedPnl": 48.6,
        "totalRealizedPnl": 28,
        "pricedCount": 1,
        "priceComplete": True,
        "items": [{
            "instrumentId": 2, "symbol": "510300", "name": "沪深300ETF",
            "quantity": 600.0, "avgCost": 4.105, "lastPrice": 4.186,
            "marketValue": 2511.6, "unrealizedPnl": 48.6, "realizedPnl": 28.0,
            "priceAsOf": "2026-10-27T01:26:25Z", "priceStatus": "VALID",
        }],
    },
    "missing": [],
}


def _result(tool: str, body: dict, ok: bool = True, error: str | None = None) -> dict:
    return {"tool": tool, "ok": ok, "data": body if ok else None, "error": error, "elapsedMs": 12}


def test_positions_summary_uses_real_numbers():
    answer = compose([_result("positions", POSITIONS_BODY)])

    conclusion = answer["conclusion"][0]
    assert "1 只标的" in conclusion
    assert "2,511.60" in conclusion
    assert "+48.60" in conclusion
    assert any("510300" in line and "600" in line for line in answer["evidence"])


def test_incomplete_price_says_so_instead_of_zero():
    body = {
        "tool": "positions",
        "data": {
            "count": 2, "totalMarketValue": 1200, "totalUnrealizedPnl": 200,
            "totalRealizedPnl": 0, "pricedCount": 1, "priceComplete": False,
            "items": [{"symbol": "600519", "name": "贵州茅台", "quantity": 100,
                       "avgCost": 10, "lastPrice": 12, "marketValue": 1200,
                       "unrealizedPnl": 200, "priceAsOf": "2026-10-27T06:30:00Z",
                       "priceStatus": "VALID"}],
        },
        "missing": ["510300 沪深300ETF 缺有效价格（priceStatus=STALE），市值未计入汇总"],
    }

    answer = compose([_result("positions", body)])

    # 汇总仍是"能取到价的部分"，必须在正文里说清楚，不能让人以为是全组合
    assert any("只含能取到价格" in line for line in answer["conclusion"])
    assert any("510300" in limit for limit in answer["limits"])


def test_failed_tool_lands_in_limits_not_conclusion():
    answer = compose([_result("positions", {}, ok=False, error="JAVA_UNAVAILABLE: 连接失败")])

    assert answer["limits"] and "positions" in answer["limits"][0]
    assert "没有取到可用的业务数据" in answer["conclusion"][0]


def test_watchlist_reports_breadth_and_extremes():
    body = {
        "tool": "watchlist-quotes",
        "data": {
            "count": 2,
            "items": [
                {"symbol": "600519", "name": "贵州茅台", "lastPrice": 1700,
                 "changePercent": 2.96, "groupName": "自选", "dataStatus": "VALID",
                 "collectedAt": "2026-10-27T06:30:00Z"},
                {"symbol": "510300", "name": "沪深300ETF", "lastPrice": 4.1,
                 "changePercent": -1.2, "groupName": "自选", "dataStatus": "VALID",
                 "collectedAt": "2026-10-27T06:30:00Z"},
            ],
        },
        "missing": [],
    }

    answer = compose([_result("watchlist-quotes", body)])

    conclusion = answer["conclusion"][0]
    assert "涨 1" in conclusion and "跌 1" in conclusion
    assert "领涨" in conclusion and "领跌" in conclusion


def test_alerts_empty_is_a_fact_not_an_error():
    answer = compose([_result("alert-events", {"tool": "alert-events", "data": {"count": 0, "items": []}, "missing": []})])

    assert "没有触发告警" in answer["conclusion"][0]
    assert not answer["limits"]


def test_no_results_returns_explicit_nothing():
    answer = compose([])

    assert "没有取到可用的业务数据" in answer["conclusion"][0]
