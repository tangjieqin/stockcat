"""复盘草稿（P2.3）：数值只能来自工具、缺数据要说、无数据不编。"""
import pytest
from fastapi.testclient import TestClient

from app.agent import llm, recap_draft, recap_service, service
from app.errors import ApiError
from app.main import app

client = TestClient(app)
DAY = "2026-10-27"


def ok(tool: str, data: dict, as_of: str | None = "2026-10-27T07:00:00Z",
       missing: list[str] | None = None) -> dict:
    return {
        "tool": tool, "ok": True, "error": None, "elapsedMs": 5,
        "data": {"tool": tool, "dataAsOf": as_of, "data": data, "missing": missing or []},
    }


BRIEF = {
    "date": DAY,
    "indices": [{
        "symbol": "000001", "name": "上证指数", "lastPrice": 3212.34, "changePercent": 0.52,
        "marketTime": "2026-10-27T07:00:00Z", "collectedAt": "2026-10-27T07:00:10Z",
    }],
    "watchlistBreadth": {"total": 3, "up": 2, "down": 1, "flat": 0, "noData": 1,
                         "avgChangePercent": 0.41},
    "note": "指数与自选行情都是采集时点的内存快照",
}

QUOTES = {"count": 3, "items": [
    {"instrumentId": 1, "symbol": "510300", "name": "沪深300ETF", "groupName": "宽基",
     "lastPrice": 4.186, "changePercent": 1.86, "dataStatus": "VALID",
     "collectedAt": "2026-10-27T07:00:05Z", "marketTime": "2026-10-27T07:00:00Z"},
    {"instrumentId": 2, "symbol": "518880", "name": "黄金ETF", "groupName": "商品",
     "lastPrice": 6.12, "changePercent": -0.31, "dataStatus": "VALID",
     "collectedAt": "2026-10-27T07:00:05Z", "marketTime": "2026-10-27T07:00:00Z"},
    {"instrumentId": 3, "symbol": "159915", "name": "创业板ETF", "groupName": "宽基",
     "lastPrice": None, "changePercent": None, "dataStatus": "STALE",
     "collectedAt": "2026-10-24T07:00:05Z", "marketTime": "2026-10-24T07:00:00Z"},
]}

POSITIONS = {"count": 1, "totalMarketValue": 2511.6, "totalUnrealizedPnl": 48.6,
             "totalRealizedPnl": 28.0, "pricedCount": 1, "priceComplete": True,
             "items": [{"instrumentId": 1, "symbol": "510300", "name": "沪深300ETF",
                        "quantity": 600.0, "avgCost": 4.105, "lastPrice": 4.186,
                        "marketValue": 2511.6, "unrealizedPnl": 48.6, "realizedPnl": 28.0,
                        "priceAsOf": "2026-10-27T01:26:25Z", "priceStatus": "VALID"}]}

ALERTS = {"count": 1, "items": [{"eventId": 9, "symbol": "600519", "ruleName": "茅台价格上穿",
                                 "ruleType": "PRICE_ABOVE", "observedPrice": 1500.0,
                                 "observedChangePercent": 2.35,
                                 "triggeredAt": "2026-10-26T14:00:00Z"}]}

RECAPS = {"count": 1, "items": [{"recapId": 3, "recapDate": "2026-10-24", "title": "周五复盘",
                                 "status": "MANUAL", "marketSummaryPreview": "指数窄幅震荡",
                                 "sectorNotesPreview": None, "contentPreview": None,
                                 "updatedAt": "2026-10-24T09:00:00Z"}]}


def full_results() -> list[dict]:
    return [
        ok("market-brief", BRIEF),
        ok("positions", POSITIONS),
        ok("watchlist-quotes", QUOTES),
        ok("alert-events", ALERTS),
        ok("recaps", RECAPS),
    ]


def empty_results() -> list[dict]:
    return [
        ok("market-brief", {"date": DAY, "indices": [],
                            "watchlistBreadth": {"total": 0, "up": 0, "down": 0, "flat": 0,
                                                 "noData": 0, "avgChangePercent": None}}),
        ok("positions", {"count": 0, "items": [], "priceComplete": True}),
        ok("watchlist-quotes", {"count": 0, "items": []}),
        ok("alert-events", {"count": 0, "items": []}),
        ok("recaps", {"count": 0, "items": []}),
    ]


@pytest.fixture
def patched_tools(monkeypatch):
    """只验证编排与契约，不连 Java。"""
    holder = {"results": full_results(), "calls": []}

    def _call(name, params=None):
        holder["calls"].append((name, params or {}))
        for result in holder["results"]:
            if result["tool"] == name:
                return result
        return ok(name, {})

    monkeypatch.setattr(service, "call_tool", _call)
    return holder


# ------------------------------------------------------------------ 事实底稿


def test_draft_quotes_only_real_numbers():
    base = recap_draft.compose(DAY, full_results())
    draft = base["draft"]

    assert "3,212.34" in draft["marketSummary"]
    assert "+0.52%" in draft["marketSummary"]
    assert "+0.41%" in draft["marketSummary"]          # 自选平均涨跌幅来自工具，不自行计算
    assert "2,511.60" in draft["content"]              # 市值千分位两位小数
    assert "4.105" in draft["content"]                 # 成本价保留原精度，不被四舍五入
    assert "+48.60" in draft["content"]
    assert "2026-10-24《周五复盘》" in draft["content"]


def test_sector_notes_admit_missing_sector_data():
    draft = recap_draft.compose(DAY, full_results())["draft"]

    assert "非行业板块" in draft["sectorNotes"]
    assert recap_draft.SECTOR_DATA_MISSING in draft["sectorNotes"]


def test_single_quote_is_not_reported_as_both_extremes():
    one = [QUOTES["items"][0]]

    assert recap_draft._extremes(one) == (None, None)


def test_group_stats_ignore_stale_quotes():
    groups = recap_draft._group_stats(QUOTES["items"])

    by_name = {group["groupName"]: group for group in groups}
    assert by_name["宽基"]["count"] == 1          # STALE 的 159915 不参与均值
    assert by_name["宽基"]["avgChangePercent"] == 1.86
    assert by_name["商品"]["avgChangePercent"] == -0.31


def test_missing_items_flow_into_limitations():
    results = full_results()
    results[1] = ok("positions", POSITIONS, missing=["159915 缺有效价格，市值未计入汇总"])

    base = recap_draft.compose(DAY, results)

    assert "159915 缺有效价格，市值未计入汇总" in base["limitations"]
    assert "159915 缺有效价格，市值未计入汇总" in base["draft"]["content"]


def test_no_material_is_reported_not_written():
    base = recap_draft.compose(DAY, empty_results())

    assert base["hasMaterial"] is False


def test_compose_keeps_title_within_column_limit():
    draft = recap_draft.compose("2026-10-27", full_results())["draft"]

    assert len(draft["title"]) <= recap_draft.MAX_TITLE_CHARS


# ------------------------------------------------------------------ 编排


def test_generate_degraded_to_rule_based_without_model(patched_tools, monkeypatch):
    monkeypatch.setattr(llm, "is_configured", lambda: False)

    body = recap_service.generate(recap_service._today())

    assert body["mode"] == "rule_based"
    assert body["draft"]["content"].strip()
    assert any("未配置模型" in item for item in body["degraded"])
    assert body["toolCalls"] and all(call["ok"] for call in body["toolCalls"])
    assert body["dataAsOf"] == "2026-10-27T07:00:00Z"


def test_generate_without_any_data_raises_no_data(patched_tools, monkeypatch):
    patched_tools["results"] = empty_results()
    monkeypatch.setattr(llm, "is_configured", lambda: False)

    with pytest.raises(ApiError) as excinfo:
        recap_service.generate(recap_service._today())

    assert excinfo.value.code == "NO_DATA"


def test_model_draft_cannot_change_limitations(patched_tools, monkeypatch):
    monkeypatch.setattr(llm, "is_configured", lambda: True)
    monkeypatch.setattr(llm, "draft_recap", lambda prompt: {
        "ok": True,
        "draft": {"title": "模型标题", "marketSummary": "模型摘要",
                  "sectorNotes": "模型板块", "content": "模型正文"},
        "error": None,
        "usage": {"model": "deepseek-chat", "promptTokens": 10, "completionTokens": 20},
        "elapsedMs": 30,
    })

    body = recap_service.generate(recap_service._today())

    assert body["mode"] == "llm"
    assert body["draft"]["title"] == "模型标题"
    assert recap_draft.SECTOR_DATA_MISSING in body["limitations"]
    assert body["usage"]["model"] == "deepseek-chat"


def test_invalid_model_output_falls_back(patched_tools, monkeypatch):
    monkeypatch.setattr(llm, "is_configured", lambda: True)
    monkeypatch.setattr(llm, "draft_recap", lambda prompt: {
        "ok": False, "draft": None,
        "error": "MODEL_OUTPUT_INVALID: 模型输出不是可解析的复盘 JSON",
        "usage": None, "elapsedMs": 12,
    })

    body = recap_service.generate(recap_service._today())

    assert body["mode"] == "rule_based"
    assert any("MODEL_OUTPUT_INVALID" in item for item in body["degraded"])
    assert body["draft"]["content"].strip()


# ------------------------------------------------------------------ 接口契约


def test_api_returns_draft_contract(patched_tools, monkeypatch):
    monkeypatch.setattr(llm, "is_configured", lambda: False)

    resp = client.post("/agent/v1/recap/draft", json={"date": DAY})

    assert resp.status_code == 200
    body = resp.json()
    assert body["date"] == DAY
    assert set(body["draft"]) == {"title", "marketSummary", "sectorNotes", "content"}
    assert isinstance(body["limitations"], list) and body["limitations"]
    assert body["dataAsOf"] == "2026-10-27T07:00:00Z"
    assert body["toolCalls"][0]["label"]


def test_api_rejects_impossible_date(patched_tools):
    resp = client.post("/agent/v1/recap/draft", json={"date": "2026-13-45"})

    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_ERROR"
