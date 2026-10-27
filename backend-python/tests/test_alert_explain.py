"""告警 AI 解释：快照缺什么就说什么，不许补算、不许模型编数。"""
import copy

import pytest
from fastapi.testclient import TestClient

from app.agent import alert_service, alert_explain
from app.main import app

client = TestClient(app)

MACD_EVENT = {
    "id": 12,
    "symbol": "600519",
    "name": "贵州茅台",
    "ruleName": "茅台 MACD 金叉",
    "ruleType": "MACD_GOLDEN_CROSS",
    "params": {},
    "observedPrice": 1500.0,
    "observedChangePercent": 2.35,
    "observedDetail": {
        "tradeDate": "2026-10-26",
        "close": 1500.0,
        "dif": 0.0123,
        "dea": 0.0101,
        "macdHist": 0.0044,
    },
    "triggeredAt": "2026-10-26T14:00:00Z",
    "quoteMarketTime": "2026-10-26T07:00:00Z",
}

CURRENT = {
    "lastPrice": 1520.0,
    "changePercent": 1.2,
    "dataStatus": "VALID",
    "marketTime": "2026-10-27T06:30:00Z",
    "collectedAt": "2026-10-27T06:30:20Z",
    "latestTradeDate": "2026-10-27",
    "indicators": {"dif": 0.008, "dea": 0.0101, "macdHist": -0.0042},
    "barsAvailable": 250,
}


def payload(event=None, current=None):
    return {"event": copy.deepcopy(event or MACD_EVENT), "current": copy.deepcopy(current)}


# ------------------------------------------------------------------ 模板生成


def test_macd_cross_quotes_snapshot_values():
    result = alert_explain.explain(payload(current=CURRENT))

    assert result["snapshotComplete"] is True
    assert "DIF 0.0123" in result["why"]
    assert "上穿" in result["why"]
    assert "DEA 0.0101" in result["why"]
    assert any("DIF=0.0123" in item for item in result["evidence"])
    assert result["condition"].startswith("条件是「DIF 上穿 DEA」")


def test_current_state_compares_and_flags_reverse_cross():
    result = alert_explain.explain(payload(current=CURRENT))

    # 触发时 DIF 在 DEA 上方，最新已回到下方 —— 必须点明曾发生反向穿越
    assert "DIF 位于 DEA 下方" in result["current"]
    assert "已发生反向穿越" in result["current"]
    assert any("最新指标" in item for item in result["evidence"])


def test_rsi_event_without_snapshot_value_never_invents_one():
    event = {
        "id": 13,
        "symbol": "600519",
        "name": "贵州茅台",
        "ruleName": "茅台 RSI6 上穿 80",
        "ruleType": "RSI_CROSS_ABOVE",
        "params": {"period": 6, "threshold": 80},
        # 事件生成时 RSI 明细没落库（历史遗留 / 早期版本）
        "observedDetail": {"tradeDate": "2026-10-24", "close": 1480.0},
        "observedPrice": 1480.0,
        "observedChangePercent": 0.8,
        "triggeredAt": "2026-10-24T14:00:00Z",
        "quoteMarketTime": "2026-10-24T07:00:00Z",
    }
    result = alert_explain.explain(payload(event, CURRENT))

    assert result["snapshotComplete"] is False
    assert "没有保存 RSI6 数值" in result["why"]
    assert any("不回算" in item for item in result["limits"])
    # 关键：不能出现任何 RSI 具体取值（阈值 80 出现在条件里是合法的，取值不行）
    assert "RSI6=" not in result["why"]
    assert "RSI6 7" not in result["why"]


def test_price_event_without_detail_still_explains():
    event = {
        "id": 14,
        "symbol": "600519",
        "name": "贵州茅台",
        "ruleName": "茅台 价格≥1000",
        "ruleType": "PRICE_ABOVE",
        "params": {"threshold": 1000},
        "observedPrice": 1023.5,
        "observedChangePercent": 1.8,
        "observedDetail": None,
        "triggeredAt": "2026-10-27T02:15:00Z",
        "quoteMarketTime": "2026-10-27T02:14:30Z",
    }
    result = alert_explain.explain({"event": event, "current": None})

    assert result["snapshotComplete"] is True
    assert "1023.5" in result["why"]
    assert "价格 ≥ ¥1000" in result["condition"]
    assert "没有取到当前数据" in result["current"]


def test_premium_event_reports_basis_and_nav_date():
    event = {
        "id": 15,
        "symbol": "161725",
        "name": "招商中证白酒",
        "ruleName": "白酒 LOF 溢价 ≥ 2%",
        "ruleType": "PREMIUM_ABOVE",
        "params": {"threshold": 2, "maxStaleDays": 3},
        "observedPrice": 0.92,
        "observedChangePercent": 0.5,
        "observedDetail": {
            "close": 0.92,
            "premium": {
                "premiumRate": 2.45,
                "premiumBasis": "NAV",
                "price": 0.92,
                "nav": 0.898,
                "navDate": "2026-10-20",
                "navStaleDays": 2,
            },
        },
        "triggeredAt": "2026-10-22T06:00:00Z",
        "quoteMarketTime": "2026-10-22T06:00:00Z",
    }
    result = alert_explain.explain({"event": event, "current": None})

    assert "溢价率 +2.45%" in result["why"]
    assert "单位净值" in result["why"]          # 基准必须说清是 NAV 还是 IOPV
    assert any("净值日期 2026-10-20" in item for item in result["evidence"])
    assert any("距今 2 天" in item for item in result["evidence"])
    # 当前溢价率没有取数，不能假装做过对比
    assert any("当前溢价率不在本次取数范围内" in item for item in result["limits"])


def test_stale_quote_is_flagged_not_treated_as_realtime():
    current = dict(CURRENT, dataStatus="STALE")
    result = alert_explain.explain(payload(current=current))

    assert "STALE" in result["current"]
    assert any("不能当作实时价" in item for item in result["limits"])


def test_market_time_before_trigger_is_called_out():
    current = dict(CURRENT, marketTime="2026-10-26T01:00:00Z")  # 早于触发时间
    result = alert_explain.explain(payload(current=current))

    assert "旧数据" in result["current"]
    assert any("早于事件触发时间" in item for item in result["limits"])


def test_indicator_rule_without_bars_says_so():
    current = dict(CURRENT, indicators=None, barsAvailable=0, latestTradeDate=None)
    result = alert_explain.explain(payload(current=current))
    assert "无法计算" in result["current"] or "没有已收盘日 K" in result["current"]


# -------------------------------------------------------------------- 编排层


def test_unconfigured_model_falls_back_to_template(monkeypatch):
    monkeypatch.setattr(alert_service.llm, "is_configured", lambda: False)
    result = alert_service.explain(payload(current=CURRENT))

    assert result["mode"] == "rule_based"
    assert any("未配置模型" in item for item in result["degraded"])
    assert "DIF 0.0123" in result["why"]


def test_model_rewrites_wording_but_not_evidence(monkeypatch):
    monkeypatch.setattr(alert_service.llm, "is_configured", lambda: True)
    monkeypatch.setattr(
        alert_service.llm,
        "analyze_alert",
        lambda base: {
            "ok": True,
            "why": "改写后的为什么",
            "meaning": "改写后的意味着什么",
            "error": None,
            "usage": {"model": "test-model", "promptTokens": 10, "completionTokens": 20},
            "elapsedMs": 5,
        },
    )
    result = alert_service.explain(payload(current=CURRENT))

    assert result["mode"] == "llm"
    assert result["why"] == "改写后的为什么"
    assert result["meaning"] == "改写后的意味着什么"
    # 数据依据与限制始终是模板产物，不受模型影响
    assert any("DIF=0.0123" in item for item in result["evidence"])
    assert result["evidence"]


def test_incomplete_snapshot_is_not_sent_to_model(monkeypatch):
    """快照缺关键值时不让模型改写：模型最容易在这里补一个"合理"数字。"""
    called: list = []
    monkeypatch.setattr(alert_service.llm, "is_configured", lambda: True)
    monkeypatch.setattr(alert_service.llm, "analyze_alert", lambda base: called.append(base) or {"ok": False})

    event = dict(MACD_EVENT, observedDetail={"tradeDate": "2026-10-26", "close": 1500.0})
    result = alert_service.explain({"event": event, "current": CURRENT})

    assert called == []
    assert result["mode"] == "rule_based"
    assert any("未经模型改写" in item for item in result["degraded"])


# -------------------------------------------------------------------- HTTP


def test_explain_endpoint_returns_camel_case(monkeypatch):
    monkeypatch.setattr(alert_service.llm, "is_configured", lambda: False)
    resp = client.post("/agent/v1/alerts/explain", json=payload(current=CURRENT))

    assert resp.status_code == 200
    body = resp.json()
    assert body["eventId"] == 12
    assert body["ruleTypeLabel"] == "MACD 金叉"
    assert body["snapshotComplete"] is True
    assert body["mode"] == "rule_based"
    for key in ("why", "meaning", "current", "evidence", "limits", "generatedAt"):
        assert key in body


def test_explain_endpoint_rejects_blank_rule_type():
    event = dict(MACD_EVENT, ruleType="")
    resp = client.post("/agent/v1/alerts/explain", json={"event": event})
    assert resp.status_code == 400
