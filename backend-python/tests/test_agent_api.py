"""AI 助手接口契约：无模型也能用、模型失败要降级、缺数据要如实说。"""
import pytest
from fastapi.testclient import TestClient

from app.agent import service
from app.main import app

client = TestClient(app)

POSITIONS_RESULT = {
    "tool": "positions",
    "ok": True,
    "error": None,
    "elapsedMs": 11,
    "data": {
        "tool": "positions",
        "dataAsOf": "2026-10-27T06:30:00Z",
        "data": {
            "count": 1,
            "totalMarketValue": 2511.6,
            "totalUnrealizedPnl": 48.6,
            "totalRealizedPnl": 28.0,
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
    },
}


@pytest.fixture
def fake_tools(monkeypatch):
    """替换真实 HTTP：只验证编排与契约，不连 Java。"""
    calls: list[str] = []

    def _call(name, params=None):
        calls.append(name)
        if name == "positions":
            return dict(POSITIONS_RESULT)
        return {"tool": name, "ok": True, "error": None, "elapsedMs": 3,
                "data": {"tool": name, "dataAsOf": None, "data": {"count": 0, "items": []}, "missing": []}}

    monkeypatch.setattr(service, "call_tool", _call)
    monkeypatch.setattr(service.planner, "plan", lambda question: [("positions", {})])
    return calls


def test_health_reports_model_not_configured():
    resp = client.get("/agent/v1/health")

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "UP"
    assert body["modelConfigured"] is False
    assert "positions" in body["tools"]
    assert "apiKey" not in resp.text.lower()


def test_ask_returns_four_part_answer_when_model_absent(fake_tools, monkeypatch):
    monkeypatch.setattr(service.llm, "is_configured", lambda: False)

    resp = client.post("/agent/v1/assistant/ask", json={"question": "我的持仓怎么样"})

    assert resp.status_code == 200
    body = resp.json()
    assert body["mode"] == "rule_based"
    assert "2,511.60" in body["answer"]["conclusion"]
    assert body["answer"]["evidence"]
    # 未配置模型必须在 degraded 里说清楚，不能让人以为是 AI 写的
    assert any("未配置模型" in item for item in body["degraded"])
    assert body["toolCalls"][0]["tool"] == "positions"
    assert body["toolCalls"][0]["label"] == "查询持仓"
    assert body["dataAsOf"] == "2026-10-27T06:30:00Z"
    assert body["generatedAt"]
    assert fake_tools == ["positions"]


def test_llm_success_merges_tool_limits(fake_tools, monkeypatch):
    monkeypatch.setattr(service.llm, "is_configured", lambda: True)
    monkeypatch.setattr(service.llm, "analyze", lambda q, r: {
        "ok": True,
        "answer": {"conclusion": "模型结论", "evidence": ["依据"], "limits": []},
        "error": None,
        "usage": {"model": "test-model", "promptTokens": 100, "completionTokens": 50},
        "elapsedMs": 800,
    })
    # 让工具返回一条缺失项，验证它不会被模型"忘掉"
    monkeypatch.setattr(service, "call_tool", lambda name, params=None: {
        **POSITIONS_RESULT,
        "data": {**POSITIONS_RESULT["data"], "missing": ["510300 缺当日收盘价"]},
    })

    resp = client.post("/agent/v1/assistant/ask", json={"question": "分析持仓"})

    body = resp.json()
    assert body["mode"] == "llm"
    assert body["model"] == "test-model"
    assert body["answer"]["conclusion"] == "模型结论"
    assert any("510300" in item for item in body["answer"]["limits"])
    assert body["degraded"] == []


def test_llm_failure_degrades_to_rule_based(fake_tools, monkeypatch):
    monkeypatch.setattr(service.llm, "is_configured", lambda: True)
    monkeypatch.setattr(service.llm, "analyze", lambda q, r: {
        "ok": False, "answer": None, "error": "MODEL_TIMEOUT: 模型调用超时（45s）",
        "usage": None, "elapsedMs": 45000,
    })

    body = client.post("/agent/v1/assistant/ask", json={"question": "分析持仓"}).json()

    assert body["mode"] == "rule_based"
    assert body["model"] is None
    assert "2,511.60" in body["answer"]["conclusion"]
    assert any("MODEL_TIMEOUT" in item for item in body["degraded"])


def test_blank_question_is_rejected():
    resp = client.post("/agent/v1/assistant/ask", json={"question": "   "})

    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_ERROR"


def test_too_long_question_is_rejected():
    resp = client.post("/agent/v1/assistant/ask", json={"question": "分" * 501})

    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_ERROR"
